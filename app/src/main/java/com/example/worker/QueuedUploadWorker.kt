package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.PixelVaultApplication
import com.example.data.repository.QueuedUploadWorkResult

class QueuedUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID)
            ?: return Result.failure()
        val application = applicationContext as? PixelVaultApplication
            ?: return Result.failure()
        return when (
            application.imageRepository.processQueuedUpload(operationId)
        ) {
            QueuedUploadWorkResult.FINISHED -> Result.success()
            QueuedUploadWorkResult.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val KEY_OPERATION_ID = "operation_id"

        fun input(operationId: String) = workDataOf(KEY_OPERATION_ID to operationId)
    }
}
