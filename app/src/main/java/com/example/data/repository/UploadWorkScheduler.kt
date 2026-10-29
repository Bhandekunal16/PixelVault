package com.example.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.worker.QueuedUploadWorker
import java.util.concurrent.TimeUnit

interface UploadWorkScheduler {
    suspend fun enqueue(operationId: String)
    suspend fun cancel(operationId: String)
}

class WorkManagerUploadScheduler(context: Context) : UploadWorkScheduler {
    private val workManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WorkManager.getInstance(context.applicationContext)
    }

    override suspend fun enqueue(operationId: String) {
        val request = OneTimeWorkRequestBuilder<QueuedUploadWorker>()
            .setInputData(QueuedUploadWorker.input(operationId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                INITIAL_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()

        workManager.enqueueUniqueWork(
            workName(operationId),
            ExistingWorkPolicy.KEEP,
            request
        ).result.get()
    }

    override suspend fun cancel(operationId: String) {
        workManager.cancelUniqueWork(workName(operationId)).result.get()
    }

    companion object {
        private const val WORK_PREFIX = "pixelvault-upload-"
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun workName(operationId: String): String = "$WORK_PREFIX$operationId"
    }
}
