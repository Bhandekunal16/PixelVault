package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ImageDao {
    @Query(
        "SELECT * FROM images WHERE accountScope = :accountScope " +
            "ORDER BY COALESCE(createdAt, '') DESC, serverId ASC"
    )
    abstract fun observeImages(accountScope: String): Flow<List<ImageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertImages(images: List<ImageEntity>)

    @Query("DELETE FROM images WHERE accountScope = :accountScope")
    abstract suspend fun deleteAllForAccount(accountScope: String)

    @Query("DELETE FROM images WHERE accountScope = :accountScope AND serverId IN (:serverIds)")
    abstract suspend fun deleteImages(accountScope: String, serverIds: List<String>)

    @Query("DELETE FROM images WHERE accountScope = :accountScope AND serverId NOT IN (:serverIds)")
    abstract suspend fun deleteMissingImages(accountScope: String, serverIds: List<String>)

    @Transaction
    open suspend fun replaceAccountImages(accountScope: String, images: List<ImageEntity>) {
        if (images.isEmpty()) {
            deleteAllForAccount(accountScope)
        } else {
            deleteMissingImages(accountScope, images.map(ImageEntity::serverId))
            upsertImages(images)
        }
    }

    @Query("SELECT * FROM image_sync_state WHERE accountScope = :accountScope")
    abstract fun observeSyncState(accountScope: String): Flow<ImageSyncStateEntity?>

    @Query("SELECT * FROM image_sync_state WHERE accountScope = :accountScope")
    abstract suspend fun getSyncState(accountScope: String): ImageSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSyncState(syncState: ImageSyncStateEntity)

    @Query("SELECT * FROM storage_usage WHERE accountScope = :accountScope")
    abstract fun observeStorageUsage(accountScope: String): Flow<StorageUsageEntity?>

    @Query("SELECT * FROM storage_usage WHERE accountScope = :accountScope")
    abstract suspend fun getStorageUsage(accountScope: String): StorageUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertStorageUsage(usage: StorageUsageEntity)

    @Query(
        "SELECT * FROM queued_uploads WHERE accountScope = :accountScope " +
            "ORDER BY createdAt DESC, operationId ASC"
    )
    abstract fun observeQueuedUploads(accountScope: String): Flow<List<QueuedUploadEntity>>

    @Query(
        "SELECT COUNT(*) FROM queued_uploads WHERE accountScope = :accountScope " +
            "AND status != 'COMPLETED' AND status != 'CANCELLED'"
    )
    abstract fun observePendingUploadCount(accountScope: String): Flow<Int>

    @Query("SELECT * FROM queued_uploads WHERE operationId = :operationId")
    abstract suspend fun getQueuedUpload(operationId: String): QueuedUploadEntity?

    @Query("SELECT * FROM queued_uploads")
    abstract suspend fun getAllQueuedUploads(): List<QueuedUploadEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertQueuedUpload(upload: QueuedUploadEntity)

    @Query(
        "UPDATE queued_uploads SET status = 'UPLOADING', attempts = attempts + 1, " +
            "updatedAt = :updatedAt, lastErrorCode = NULL, lastErrorMessage = NULL " +
            "WHERE operationId = :operationId AND accountScope = :accountScope " +
            "AND status IN ('QUEUED', 'RETRYABLE_FAILURE', 'UPLOADING')"
    )
    abstract suspend fun beginQueuedUpload(
        operationId: String,
        accountScope: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE queued_uploads SET status = 'QUEUED', updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND accountScope = :accountScope AND status = 'UPLOADING'"
    )
    abstract suspend fun recoverInterruptedUpload(
        operationId: String,
        accountScope: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE queued_uploads SET status = :status, lastErrorCode = :errorCode, " +
            "lastErrorMessage = :message, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND accountScope = :accountScope"
    )
    abstract suspend fun setQueuedUploadFailure(
        operationId: String,
        accountScope: String,
        status: String,
        errorCode: String?,
        message: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE queued_uploads SET status = 'WAITING_FOR_ACCOUNT', lastErrorCode = 'AUTH_REQUIRED', " +
            "lastErrorMessage = :message, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND accountScope = :accountScope"
    )
    abstract suspend fun pauseQueuedUploadForAccount(
        operationId: String,
        accountScope: String,
        message: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE queued_uploads SET status = 'QUEUED', lastErrorCode = NULL, " +
            "lastErrorMessage = NULL, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND accountScope = :accountScope " +
            "AND status IN ('RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'WAITING_FOR_ACCOUNT')"
    )
    abstract suspend fun retryQueuedUpload(
        operationId: String,
        accountScope: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE queued_uploads SET status = 'CANCELLED', localFilePath = NULL, " +
            "lastErrorCode = NULL, lastErrorMessage = NULL, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND accountScope = :accountScope " +
            "AND status IN ('QUEUED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'WAITING_FOR_ACCOUNT')"
    )
    abstract suspend fun setQueuedUploadCancelled(
        operationId: String,
        accountScope: String,
        updatedAt: Long
    ): Int

    @Transaction
    open suspend fun cancelQueuedUpload(
        operationId: String,
        accountScope: String,
        updatedAt: Long
    ): String? {
        val upload = getQueuedUpload(operationId) ?: return null
        if (upload.accountScope != accountScope || upload.localFilePath == null) return null
        return if (setQueuedUploadCancelled(operationId, accountScope, updatedAt) == 1) {
            upload.localFilePath
        } else {
            null
        }
    }

    @Transaction
    open suspend fun completeQueuedUpload(
        operationId: String,
        accountScope: String,
        image: ImageEntity,
        updatedAt: Long
    ): String? {
        val upload = getQueuedUpload(operationId) ?: return null
        if (
            upload.accountScope != accountScope ||
            upload.status == UploadQueueStatus.CANCELLED ||
            upload.status == UploadQueueStatus.COMPLETED
        )
            return null

        upsertImages(listOf(image))
        val changed = setQueuedUploadCompleted(
            operationId = operationId,
            accountScope = accountScope,
            serverImageId = image.serverId,
            updatedAt = updatedAt
        )
        return if (changed == 1) upload.localFilePath else null
    }

    @Query(
        "UPDATE queued_uploads SET status = 'COMPLETED', " +
            "serverImageId = :serverImageId, lastErrorCode = NULL, lastErrorMessage = NULL, " +
            "updatedAt = :updatedAt WHERE operationId = :operationId " +
            "AND accountScope = :accountScope AND status != 'CANCELLED'"
    )
    abstract suspend fun setQueuedUploadCompleted(
        operationId: String,
        accountScope: String,
        serverImageId: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE queued_uploads SET localFilePath = NULL, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND accountScope = :accountScope " +
            "AND status = 'COMPLETED'"
    )
    abstract suspend fun clearCompletedUploadFilePath(
        operationId: String,
        accountScope: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE queued_uploads SET status = 'QUEUED', updatedAt = :updatedAt " +
            "WHERE accountScope = :accountScope AND status = 'UPLOADING'"
    )
    abstract suspend fun recoverInterruptedUploads(accountScope: String, updatedAt: Long): Int

    @Query(
        "DELETE FROM queued_uploads WHERE operationId = :operationId " +
            "AND accountScope = :accountScope " +
            "AND status IN ('COMPLETED', 'CANCELLED', 'PERMANENT_FAILURE', 'RETRYABLE_FAILURE', 'WAITING_FOR_ACCOUNT')"
    )
    abstract suspend fun deleteQueuedUpload(operationId: String, accountScope: String): Int
}
