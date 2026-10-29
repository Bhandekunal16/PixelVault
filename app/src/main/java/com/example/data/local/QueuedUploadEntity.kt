package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object UploadQueueStatus {
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val RETRYABLE_FAILURE = "RETRYABLE_FAILURE"
    const val PERMANENT_FAILURE = "PERMANENT_FAILURE"
    const val WAITING_FOR_ACCOUNT = "WAITING_FOR_ACCOUNT"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

@Entity(
    tableName = "queued_uploads",
    indices = [
        Index(value = ["accountScope", "status"]),
        Index(value = ["accountScope", "createdAt"])
    ]
)
data class QueuedUploadEntity(
    @PrimaryKey val operationId: String,
    val accountScope: String,
    val localFilePath: String?,
    val originalFilename: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val outputFormat: String,
    val status: String,
    val attempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val serverImageId: String? = null
)
