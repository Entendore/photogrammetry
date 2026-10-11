package com.example.photogrammetry

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.photogrammetry.sfm.ImageQuality
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread

class ScanActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        fun intent(ctx: Context, scanId: String) =
            Intent(ctx, ScanActivity::class.java).putExtra(EXTRA_SCAN, scanId)
        private const val EXTRA_SCAN = "scan_id"
        private const val BLUR_MIN = 45.0
        private const val AUTO_INTERVAL_MS = 1500L
        private const val TIPS_KEY = "scan_tips_shown"

        // Portrait-locked activity + standard 90° phone sensor. If photos on a rare
        // device come out sideways, change this to 180f or 270f.
        private const val CAPTURE_ROTATION_DEG = 90f
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var flashView: android.view.View
    private lateinit var thumbRow: LinearLayout
    private lateinit var scanButton: Button

    private lateinit var scanId: String
    private lateinit var photosDir: File

    private var session: Session? = null
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var pointCloudRenderer: PointCloudRenderer

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val camPos = FloatArray(3)

    private val cloudLock = Any()
    private val maxPoints = 2_000_000
    private val accumulated = FloatArray(maxPoints)
    private var accumulatedCount = 0
    private var lastUploaded = 0
    private val seenIds = HashSet<Int>()
    private val confidenceMin = 0.5f

    @Volatile private var scanning = false
    @Volatile private var cloudFull = false
    @Volatile private var requestCapture = false
    @Volatile private var autoCapture = false
    private var lastAuto = 0L
    private var lastUiUpdate = 0L
    private var photoCount = 0

    private val io = Executors.newSingleThreadExecutor()

    private val camPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAr()
            else {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        setContentView(R.layout.activity_scan)

        scanId = intent.getStringExtra(EXTRA_SCAN) ?: Scans.create(this)
        photosDir = Scans.photosDir(this, scanId)
        photoCount = photosDir.listFiles { f -> f.extension.lowercase() == "jpg" }?.size ?: 0

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        glView = findViewById(R.id.gl_view)
        statusText = findViewById(R.id.status_text)
        flashView = findViewById(R.id.flash)
        thumbRow = findViewById(R.id.thumb_row)
        scanButton = findViewById(R.id.btn_scan)

        glView.setEGLContextClientVersion(2)
        glView.setRenderer(this)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glView.setOnClickListener { requestCapture = true }
        findViewById<Button>(R.id.btn_photo).setOnClickListener { requestCapture = true }

        scanButton.setOnClickListener {
            scanning = !scanning
            scanButton.text = if (scanning) "Stop scan" else "Start scan"
        }
        findViewById<Switch>(R.id.sw_auto).setOnCheckedChangeListener { _, checked ->
            autoCapture = checked
        }
        findViewById<Button>(R.id.btn_export).setOnClickListener { exportPly() }

        photosDir.listFiles { f -> f.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }?.takeLast(12)?.forEach { addThumb(it) }

        if (getPreferences(Context.MODE_PRIVATE).getBoolean(TIPS_KEY, true)) showTips()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startAr()
        else camPermission.launch(Manifest.permission.CAMERA)
    }

    private fun showTips() {
        AlertDialog.Builder(this)
            .setTitle("Scanning tips")
            .setMessage(
                "• Move slowly around the object\n" +
                "• Capture a photo every small step (60–80% overlap)\n" +
                "• Or flip on Auto and orbit slowly — it shoots for you\n" +
                "• Avoid glass, mirrors, and blank uniform surfaces")
            .setPositiveButton("Got it") { _, _ ->
                getPreferences(Context.MODE_PRIVATE).edit().putBoolean(TIPS_KEY, false).apply()
            }.show()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------------- ARCore session ----------------

    private fun ensureArCoreInstalled(): Boolean = try {
        when (ArCoreApk.getInstance().requestInstall(this, true)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> false
            else -> true
        }
    } catch (e: Exception) {
        Toast.makeText(this, "ARCore unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        false
    }

    private fun createSession() {
        val s = Session(this)
        try {
            // cameraConfig belongs to the Session — pick the largest config that
            // still supports depth, so photos stay sharp AND the cloud keeps working.
            s.supportedCameraConfigs
                .filter { it.isDepthSupported }
                .maxByOrNull { it.imageDimensions[0] * it.imageDimensions[1] }
                ?.let { s.cameraConfig = it }
        } catch (_: Exception) {}
        val config = Config(s).apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                depthMode = Config.DepthMode.AUTOMATIC
        }
        s.configure(config)
        session = s
    }

    private fun startAr() {
        if (!ensureArCoreInstalled()) return
        if (session == null) createSession()
        try {
            session?.resume()
            glView.onResume()
        } catch (e: Exception) {
            Toast.makeText(this, "ARCore error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED && session != null) {
            try { session?.resume(); glView.onResume() } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        glView.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        session?.close()
        super.onDestroy()
    }

    // ---------------- Rendering ----------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = BackgroundRenderer().apply { createOnGlThread() }
        pointCloudRenderer = PointCloudRenderer().apply { createOnGlThread() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val rotation = try {
            if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        } catch (_: Exception) { Surface.ROTATION_0 }
        session?.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        s.setCameraTextureName(backgroundRenderer.textureId)
        val frame = try { s.update() } catch (_: Throwable) { return }
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            updateStatus("Move slowly to initialize tracking…")
            return
        }

        camera.getProjectionMatrix(0.05f, 40f, projectionMatrix, 0)
        camera.getViewMatrix(viewMatrix, 0)
        camera.pose.getTranslation(camPos, 0)

        if (requestCapture) { requestCapture = false; grabFrame(frame, manual = true) }
        if (autoCapture && SystemClock.uptimeMillis() - lastAuto > AUTO_INTERVAL_MS) {
            lastAuto = SystemClock.uptimeMillis()
            grabFrame(frame, manual = false)
        }
        if (scanning && !cloudFull) {
            try { accumulatePointCloud(frame) } catch (_: Throwable) {}
        }

        synchronized(cloudLock) {
            if (accumulatedCount != lastUploaded) {
                pointCloudRenderer.update(
                    FloatBuffer.wrap(accumulated, 0, accumulatedCount),
                    accumulatedCount / 3)
                lastUploaded = accumulatedCount
            }
        }
        pointCloudRenderer.draw(projectionMatrix, viewMatrix, camPos)

        updateStatus("Photos: $photoCount   Points: ${accumulatedCount / 3}" +
                (if (cloudFull) " (buffer full)" else ""))
    }

    // ---------------- Capture ----------------

    private fun grabFrame(frame: Frame, manual: Boolean) {
        val img = try { frame.acquireCameraImage() } catch (_: Throwable) { return }
        val ci = frame.camera.imageIntrinsics
        val fx = ci.focalLength[0].toDouble(); val fy = ci.focalLength[1].toDouble()
        val cx = ci.principalPoint[0].toDouble(); val cy = ci.principalPoint[1].toDouble()
        io.execute { saveCapture(img, fx, fy, cx, cy, manual) }
    }

    /** Runs on the IO executor. Saves an UPRIGHT jpeg + matching rotated intrinsics. */
    private fun saveCapture(image: Image, fx: Double, fy: Double,
                            cx: Double, cy: Double, manual: Boolean) {
        try {
            val sharp = ImageQuality.sharpness(image) ?: return
            if (sharp < BLUR_MIN) {
                if (manual) uiStatus("⚠ Blurry (${sharp.toInt()}) — hold steadier")
                return
            }

            val w = image.width; val h = image.height
            val nv21 = yuv420ToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val jpg = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, w, h), 92, jpg)
            var bmp = BitmapFactory.decodeByteArray(jpg.toByteArray(), 0, jpg.size()) ?: return

            val upright = Bitmap.createBitmap(bmp, 0, 0, w, h,
                Matrix().apply { postRotate(CAPTURE_ROTATION_DEG) }, true)
            if (upright != bmp) bmp.recycle()
            bmp = upright

            val base = "IMG_${System.currentTimeMillis()}"
            File(photosDir, "$base.jpg").outputStream()
                .use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }

            // Rotate intrinsics to match the upright bitmap:
            // 90° CW pixel map (u,v)->(h−v,u): f'=(fy,fx), c'=(h−cy,cx), dims swap.
            val intr = when (CAPTURE_ROTATION_DEG) {
                90f  -> doubleArrayOf(fy, fx, h - cy, cx, h.toDouble(), w.toDouble())
                180f -> doubleArrayOf(fx, fy, w - cx, h - cy, w.toDouble(), h.toDouble())
                270f -> doubleArrayOf(fy, fx, cy, w - cx, h.toDouble(), w.toDouble())
                else -> doubleArrayOf(fx, fy, cx, cy, w.toDouble(), h.toDouble())
            }
            File(photosDir, "$base.json").writeText(JSONObject()
                .put("fx", intr[0]).put("fy", intr[1])
                .put("cx", intr[2]).put("cy", intr[3])
                .put("w", intr[4].toInt()).put("h", intr[5].toInt()).toString())

            photoCount++
            val saved = File(photosDir, "$base.jpg")
            runOnUiThread {
                addThumb(saved)
                if (manual) {
                    glView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    flashView.animate().alpha(0.5f).setDuration(40).withEndAction {
                        flashView.animate().alpha(0f).setDuration(150).start()
                    }.start()
                }
            }
        } catch (_: Throwable) {
        } finally {
            image.close()
        }
    }

    private fun addThumb(f: File) {
        val iv = ImageView(this)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        var sample = 1
        while (b.outWidth / (sample * 2) >= 96) sample *= 2
        iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }))
        iv.layoutParams = LinearLayout.LayoutParams(96, 96).apply { marginEnd = 8 }
        thumbRow.addView(iv, 0)
        if (thumbRow.childCount > 12) thumbRow.removeViewAt(thumbRow.childCount - 1)
    }

    private fun uiStatus(s: String) = runOnUiThread { statusText.text = s }

    // ---------------- Point cloud accumulation ----------------

    private fun accumulatePointCloud(frame: Frame) {
        frame.acquirePointCloud().use { pc ->
            val data = pc.buffer
            val ids = pc.ids
            synchronized(cloudLock) {
                while (ids.hasRemaining() && data.remaining() >= 16) {
                    val id = ids.float.toRawBits()
                    val x = data.float; val y = data.float; val z = data.float
                    val confidence = data.float
                    if (confidence > confidenceMin && seenIds.add(id)) {
                        if (accumulatedCount + 3 > maxPoints) { cloudFull = true; return }
                        accumulated[accumulatedCount++] = x
                        accumulated[accumulatedCount++] = y
                        accumulated[accumulatedCount++] = z
                    }
                }
            }
        }
    }

    // ---------------- Export + share ----------------

    private fun exportPly() {
        val copy = synchronized(cloudLock) { accumulated.copyOf(accumulatedCount) }
        if (copy.isEmpty()) {
            Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            val dir = File(Scans.root(this), scanId)
            val f = File(dir, "ar_points.ply")
            val ok = PlyExporter.writeAscii(f, copy, copy.size / 3)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Saved ar_points.ply", Toast.LENGTH_SHORT).show()
                    Share.file(this, f)
                } else Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus(text: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastUiUpdate > 250) {
            lastUiUpdate = now
            runOnUiThread { statusText.text = text }
        }
    }

    // ---------------- YUV → NV21 ----------------

    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width; val h = image.height
        val ySize = w * h
        val nv21 = ByteArray(ySize + ySize / 2)
        val yPlane = image.planes[0]; val yBuf = yPlane.buffer
        var pos = 0
        if (yPlane.rowStride == w && yPlane.pixelStride == 1) {
            yBuf.get(nv21, 0, ySize); pos = ySize
        } else {
            for (row in 0 until h) {
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(nv21, pos, w); pos += w
            }
        }
        val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            nv21[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
            nv21[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
        }
        return nv21
    }
}
