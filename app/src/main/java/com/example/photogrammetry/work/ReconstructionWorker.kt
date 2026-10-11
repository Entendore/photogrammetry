package com.example.photogrammetry.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.photogrammetry.Scans
import com.example.photogrammetry.sfm.SfmPipeline
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import java.io.File

class ReconstructionWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SCAN = "scan_id"
        const val KEY_PROGRESS = "progress_msg"
        const val KEY_POINTS = "point_count"
        const val KEY_ERROR = "error_msg"
        private const val CHANNEL_ID = "reconstruction"
        private const val NOTIF_ID = 42

        fun uniqueName(scanId: String) = "recon_$scanId"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo("Preparing reconstruction…")

    override suspend fun doWork(): Result {
        val scanId = inputData.getString(KEY_SCAN)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing scan id"))
        val photosDir = Scans.photosDir(applicationContext, scanId)
        val outDir = Scans.outDir(applicationContext, scanId)
        val files = photosDir.listFiles { f ->
            f.extension.lowercase() in listOf("jpg", "jpeg", "png")
        }?.sortedBy { it.name } ?: emptyList()
        if (files.size < 3)
            return Result.failure(workDataOf(KEY_ERROR to
                "Need ≥3 photos (have ${files.size})."))

        setForeground(buildForegroundInfo("Reconstructing $scanId…"))
        OpenCVLoader.initLocal()

        val progressCh = Channel<String>(Channel.CONFLATED)
        var lastSent = 0L
        val pipeline = SfmPipeline(photosDir, files, isCancelled = { isStopped }) { msg ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastSent > 250) { lastSent = now; progressCh.trySend(msg) }
        }
        val pump = launch {
            for (msg in progressCh) setProgress(workDataOf(KEY_PROGRESS to msg))
        }

        return try {
            val ok = pipeline.run()
            pump.cancel()
            if (ok) {
                pipeline.export(outDir)
                pipeline.exportColmap(File(outDir, "colmap_sparse"))
                Result.success(workDataOf(KEY_POINTS to pipeline.tracks.count { it.valid }))
            } else {
                Result.failure(workDataOf(KEY_ERROR to
                        if (isStopped) "Cancelled."
                        else "Reconstruction failed — capture more overlapping photos."))
            }
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to
                "Error: ${t.message ?: t.javaClass.simpleName}"))
        }
    }

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                "Reconstruction", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Photogrammetry")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, notification)
    }
}
