package com.example.photogrammetry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.photogrammetry.work.ReconstructionWorker
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var arStatus: TextView
    private var arReady = false

    private val camPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startNewScan()
            else Toast.makeText(this, "Camera permission is required to scan.",
                Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arStatus = findViewById(R.id.ar_status)
        rv = findViewById(R.id.rv_scans)
        rv.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btn_new_scan).setOnClickListener {
            val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            when {
                !hasCam -> camPermission.launch(Manifest.permission.CAMERA)
                arReady -> startNewScan()
                else -> Toast.makeText(this,
                    "ARCore is still preparing — try again in a moment.", Toast.LENGTH_SHORT).show()
            }
        }
        checkArSupport()
    }

    private fun checkArSupport() {
        when (val a = ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.UNKNOWN_CHECKING ->
                rv.postDelayed({ checkArSupport() }, 250)
            else -> {
                arReady = a.isSupported
                arStatus.text = if (arReady) "ARCore ✓"
                    else "ARCore is not supported on this device — scanning unavailable."
            }
        }
    }

    private fun startNewScan() =
        startActivity(ScanActivity.intent(this, Scans.create(this)))

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val scans = Scans.list(this)
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) {
                scans.filter { isRunning(it.id) }.map { it.id }.toSet()
            }
            rv.adapter = ScanAdapter(scans, running) { scan ->
                startActivity(ReconstructActivity.intent(this@MainActivity, scan.id))
            }
        }
    }

    private fun isRunning(scanId: String): Boolean = try {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWork(ReconstructionWorker.uniqueName(scanId))
            .get()
            .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    } catch (_: Exception) { false }

    class ScanAdapter(private val items: List<ScanInfo>,
                      private val running: Set<String>,
                      private val onClick: (ScanInfo) -> Unit) :
        RecyclerView.Adapter<ScanAdapter.VH>() {

        class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.scan_title)
            val sub: TextView = v.findViewById(R.id.scan_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_scan, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.title.text = s.id.replace('_', ' ')
            h.sub.text = "${s.photoCount} photos" +
                (if (s.id in running) " · ⏳ reconstructing…"
                 else if (s.hasCloud) " · reconstruction ✓" else "")
            h.itemView.setOnClickListener { onClick(s) }
        }
    }
}
