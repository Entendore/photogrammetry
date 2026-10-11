package com.example.photogrammetry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import com.example.photogrammetry.work.ReconstructViewModel
import com.example.photogrammetry.work.ReconstructionWorker
import java.io.File

class ReconstructActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, scanId: String) =
            Intent(ctx, ReconstructActivity::class.java).putExtra("scan_id", scanId)
    }

    private val vm: ReconstructViewModel by viewModels()

    private lateinit var scanId: String
    private lateinit var photosDir: File
    private lateinit var outDir: File

    private lateinit var status: TextView
    private lateinit var btnRecon: Button
    private lateinit var btnCancel: Button
    private lateinit var btnShare: Button
    private lateinit var btnCloud: Button
    private lateinit var btnMesh: Button
    private lateinit var btnAr: Button

    private val pointsFile get() = File(outDir, "sfm_points.ply")

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { enqueue() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_reconstruct)

        scanId = intent.getStringExtra("scan_id") ?: run { finish(); return }
        photosDir = Scans.photosDir(this, scanId)
        outDir = Scans.outDir(this, scanId)

        findViewById<TextView>(R.id.scan_title).text = scanId.replace('_', ' ')

        status = findViewById(R.id.status_text)
        btnRecon = findViewById(R.id.btn_reconstruct)
        btnCancel = findViewById(R.id.btn_cancel)
        btnShare = findViewById(R.id.btn_share)
        btnCloud = findViewById(R.id.btn_view_cloud)
        btnMesh = findViewById(R.id.btn_view_mesh)
        btnAr = findViewById(R.id.btn_ar)

        btnRecon.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else enqueue()
        }
        btnCancel.setOnClickListener { vm.cancel(scanId) }
        btnCloud.setOnClickListener {
            startActivity(PointCloudViewerActivity.intent(this, pointsFile.absolutePath))
        }
        btnShare.setOnClickListener { Share.file(this, pointsFile) }
        btnMesh.setOnClickListener { startActivity(Intent(this, MeshViewerActivity::class.java)) }
        btnAr.setOnClickListener { startActivity(Intent(this, ArPlacementActivity::class.java)) }

        // Re-attaches automatically after rotation, back-nav, even process restart
        vm.observe(scanId).observe(this) { render(it.firstOrNull()) }
    }

    private fun enqueue() {
        val n = photosDir.listFiles { f ->
            f.extension.lowercase() in listOf("jpg", "jpeg", "png") }?.size ?: 0
        if (n < 3) {
            Toast.makeText(this, "Need ≥3 photos (have $n).", Toast.LENGTH_LONG).show()
            return
        }
        vm.enqueue(scanId)
    }

    private fun render(wi: WorkInfo?) {
        val hasPoints = pointsFile.exists()
        when (wi?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                setBusy(true, hasPoints)
                status.text = wi.progress.getString(ReconstructionWorker.KEY_PROGRESS)
                    ?: "Queued — starting reconstruction…"
            }
            WorkInfo.State.SUCCEEDED -> {
                setBusy(false, true)
                status.text = "Done ✓ ${wi.outputData.getInt(ReconstructionWorker.KEY_POINTS, -1)}" +
                        " points\n$pointsFile\n\nNext: Poisson-mesh the PLY in MeshLab → " +
                        "export .glb into models/ → open Mesh viewer or AR."
            }
            WorkInfo.State.FAILED -> {
                setBusy(false, hasPoints)
                status.text = wi.outputData.getString(ReconstructionWorker.KEY_ERROR)
                    ?: "Reconstruction failed."
            }
            WorkInfo.State.CANCELLED -> { setBusy(false, hasPoints); status.text = "Cancelled." }
            else -> {
                setBusy(false, hasPoints)
                status.text = if (hasPoints)
                    "Reconstruction available:\n$pointsFile"
                else "No reconstruction yet.\nTap Reconstruct — it runs in the background and " +
                        "survives app switch, rotation, and even force-stop."
            }
        }
    }

    private fun setBusy(busy: Boolean, hasPoints: Boolean) {
        btnRecon.isEnabled = !busy
        btnCancel.isEnabled = busy
        btnCloud.isEnabled = hasPoints
        btnShare.isEnabled = hasPoints
        btnMesh.isEnabled = !busy
        btnAr.isEnabled = !busy
    }
}
