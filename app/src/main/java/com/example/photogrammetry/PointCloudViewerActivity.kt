package com.example.photogrammetry

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class PointCloudViewerActivity : Activity() {

    companion object {
        fun intent(c: Context, path: String) =
            Intent(c, PointCloudViewerActivity::class.java).putExtra("path", path)
    }

    private lateinit var gl: GLSurfaceView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val renderer = CloudRenderer(path)

        var lastX = 0f; var lastY = 0f; var lastTap = 0L
        gl = object : GLSurfaceView(this) {
            val pinch = ScaleGestureDetector(context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        renderer.dist = (renderer.dist / d.scaleFactor)
                            .coerceIn(renderer.radius * 0.1f, renderer.radius * 20f)
                        return true
                    }
                })

            override fun onTouchEvent(e: MotionEvent): Boolean {
                pinch.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                    MotionEvent.ACTION_MOVE -> {
                        renderer.yaw += (e.x - lastX) * 0.01f
                        renderer.pitch = (renderer.pitch + (e.y - lastY) * 0.01f)
                            .coerceIn(-1.5f, 1.5f)
                        lastX = e.x; lastY = e.y
                    }
                    MotionEvent.ACTION_UP -> {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTap < 300) {      // double-tap = re-frame
                            renderer.yaw = 0.7f; renderer.pitch = 0.35f
                            renderer.dist = renderer.radius * 1.8f
                        }
                        lastTap = now
                    }
                }
                return true
            }
        }
        gl.setEGLContextClientVersion(2)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(gl)
    }

    class CloudRenderer(path: String) : GLSurfaceView.Renderer {
        private var program = 0; private var aPos = 0; private var aCol = 0
        private var uMvp = 0; private var uSize = 0
        private var vbo = 0; private var count = 0
        @Volatile private var pending: FloatArray? = null

        var yaw = 0.7f; var pitch = 0.35f; var dist = 2f
        var radius = 1f
        private val center = FloatArray(3)
        private val proj = FloatArray(16); private val view = FloatArray(16)
        private val mvp = FloatArray(16)

        init { Thread { parse(path) }.start() }

        private fun parse(path: String) {
            try {
                File(path).inputStream().buffered(1 shl 16).use { input ->
                    var binary = false; var vertexCount = 0
                    val header = StringBuilder()
                    while (true) {
                        val line = input.readLine() ?: return
                        if (line.contains("binary_little_endian")) binary = true
                        if (line.startsWith("element vertex"))
                            vertexCount = line.substringAfterLast(' ').trim().toInt()
                        header.append(line).append('\n')
                        if (line.startsWith("end_header")) break
                    }
                    if (vertexCount <= 0) return
                    val hasColor = header.contains("property uchar red")
                    val stride = if (hasColor) 15 else 12
                    val maxKeep = 1_500_000
                    val step = maxOf(1, vertexCount / maxKeep)

                    val keep = ArrayList<Float>(minOf(vertexCount, maxKeep) * 6)
                    var minx = Float.MAX_VALUE; var miny = minx; var minz = minx
                    var maxx = -minx; var maxy = -minx; var maxz = -minx
                    val byteBuf = ByteArray(stride)

                    fun emit(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
                        keep += floatArrayOf(x, y, z, r, g, b)
                        minx = minOf(minx, x); miny = minOf(miny, y); minz = minOf(minz, z)
                        maxx = maxOf(maxx, x); maxy = maxOf(maxy, y); maxz = maxOf(maxz, z)
                    }

                    fun readFully(): Boolean {
                        var off = 0
                        while (off < stride) {
                            val n = input.read(byteBuf, off, stride - off)
                            if (n < 0) return false
                            off += n
                        }
                        return true
                    }

                    if (binary) {
                        val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN)
                        var idx = 0L
                        while (idx < vertexCount) {
                            if (!readFully()) break
                            if (idx % step == 0L) {
                                bb.rewind()
                                emit(bb.float, bb.float, bb.float,
                                    if (hasColor) (byteBuf[12].toInt() and 0xFF) / 255f else 0.2f,
                                    if (hasColor) (byteBuf[13].toInt() and 0xFF) / 255f else 1f,
                                    if (hasColor) (byteBuf[14].toInt() and 0xFF) / 255f else 0.4f)
                            }
                            idx++
                        }
                    } else {
                        var idx = 0
                        input.bufferedReader().forEachLine { line ->
                            if (idx >= vertexCount) return@forEachLine
                            if (idx % step == 0) {
                                val t = line.trim().split(Regex("\\s+"))
                                if (t.size >= 3) emit(t[0].toFloat(), t[1].toFloat(),
                                    t[2].toFloat(),
                                    if (hasColor && t.size >= 6) t[3].toFloat()/255f else 0.2f,
                                    if (hasColor && t.size >= 6) t[4].toFloat()/255f else 1f,
                                    if (hasColor && t.size >= 6) t[5].toFloat()/255f else 0.4f)
                            }
                            idx++
                        }
                    }

                    if (keep.isEmpty()) return
                    center[0] = (minx + maxx)/2; center[1] = (miny + maxy)/2
                    center[2] = (minz + maxz)/2
                    radius = maxOf(maxx-minx, maxy-miny, maxz-minz, 0.01f)
                    dist = radius * 1.8f
                    pending = keep.toFloatArray()
                    count = keep.size / 6
                }
            } catch (_: Exception) {}
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1f)
            program = ShaderUtil.link(
                ShaderUtil.compile(GLES20.GL_VERTEX_SHADER, VERTEX),
                ShaderUtil.compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            aPos = GLES20.glGetAttribLocation(program, "a_Position")
            aCol = GLES20.glGetAttribLocation(program, "a_Color")
            uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
            uSize = GLES20.glGetUniformLocation(program, "u_Size")
            val vbos = IntArray(1); GLES20.glGenBuffers(1, vbos, 0); vbo = vbos[0]
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            Matrix.perspectiveM(proj, 0, 50f, w.toFloat()/h.coerceAtLeast(1), 0.01f, 1000f)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (count == 0) return
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            pending?.let { data ->                       // one-time upload on GL thread
                val fb = ByteBuffer.allocateDirect(data.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                    .apply { put(data); position(0) }
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb,
                    GLES20.GL_STATIC_DRAW)
                pending = null
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            val eye = floatArrayOf(
                center[0] + dist * cos(pitch) * sin(yaw),
                center[1] + dist * sin(pitch),
                center[2] + dist * cos(pitch) * cos(yaw))
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2],
                center[0], center[1], center[2], 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniform1f(uSize, (radius * 40f / dist).coerceIn(2f, 8f))
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 24, 12)
            GLES20.glEnableVertexAttribArray(aCol)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aCol)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        companion object {
            private const val VERTEX = """
                uniform mat4 u_Mvp; uniform float u_Size;
                attribute vec3 a_Position; attribute vec3 a_Color;
                varying vec3 v_Color;
                void main() { gl_Position = u_Mvp * vec4(a_Position, 1.0);
                    gl_PointSize = u_Size; v_Color = a_Color; }"""
            private const val FRAGMENT = """
                precision mediump float; varying vec3 v_Color;
                void main() { gl_FragColor = vec4(v_Color, 1.0); }"""
        }
    }
}
