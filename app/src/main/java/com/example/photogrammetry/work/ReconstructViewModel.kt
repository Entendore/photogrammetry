package com.example.photogrammetry.work

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

class ReconstructViewModel(app: Application) : AndroidViewModel(app) {
    private val wm = WorkManager.getInstance(app)

    fun enqueue(scanId: String) {
        val req = OneTimeWorkRequestBuilder<ReconstructionWorker>()
            .setInputData(workDataOf(ReconstructionWorker.KEY_SCAN to scanId))
            .build()
        wm.enqueueUniqueWork(ReconstructionWorker.uniqueName(scanId),
            ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(scanId: String) = wm.cancelUniqueWork(ReconstructionWorker.uniqueName(scanId))

    fun observe(scanId: String): LiveData<List<WorkInfo>> =
        wm.getWorkInfosForUniqueWorkLiveData(ReconstructionWorker.uniqueName(scanId))
}
