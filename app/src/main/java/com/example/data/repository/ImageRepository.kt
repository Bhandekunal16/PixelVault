package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.api.ApiService
import com.example.data.api.NetworkClient
import com.example.data.api.NetworkErrorHandler
import com.example.data.local.ImageDao
import com.example.data.local.ImageDatabase
import com.example.data.local.ImageEntity
import com.example.data.local.ImageSyncStateEntity
import com.example.data.local.PendingUploadFileStore
import com.example.data.local.QueuedUploadEntity
import com.example.data.local.StorageUsageEntity
import com.example.data.local.StagedUploadFile
import com.example.data.local.UploadQueueStatus
import com.example.data.local.toEntity
import com.example.data.model.ImageMetadata
import com.example.data.model.OutputFormat
import com.example.data.model.StorageUsage
import com.example.data.preferences.AppPreferences
import com.example.util.FileUtils
import com.example.util.Resource
import com.example.util.ValidationUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

interface ImageRepository {
    fun uploadImage(
        context: Context,
        uri: Uri,
        email: String,
        format: OutputFormat
    ): Flow<Resource<ImageMetadata>>

    fun observeImages(email: String): Flow<List<ImageMetadata>>

    fun observeSyncState(email: String): Flow<ImageSyncStateEntity?>

    fun observeStorageUsage(email: String): Flow<StorageUsageEntity?>

    fun observeQueuedUploads(email: String): Flow<List<QueuedUploadEntity>>

    fun observePendingUploadCount(email: String): Flow<Int>

    suspend fun queueUpload(
        context: Context,
        uri: Uri,
        email: String,
        format: OutputFormat
    ): Resource<QueuedUploadEntity>

    suspend fun retryQueuedUpload(operationId: String, email: String): Resource<Unit>

    suspend fun cancelQueuedUpload(operationId: String, email: String): Resource<Unit>

    suspend fun removeQueuedUpload(operationId: String, email: String): Resource<Unit>

    suspend fun resumeQueuedUploads(email: String)

    suspend fun cleanupQueuedUploadFiles()

    suspend fun processQueuedUpload(operationId: String): QueuedUploadWorkResult

    suspend fun refreshStorageUsage(
        email: String,
        forceRefresh: Boolean = false
    ): Resource<StorageUsage>

    suspend fun synchronizeImages(email: String, forceRefresh: Boolean = false): Resource<Unit>

    suspend fun removeCachedImages(email: String, ids: List<String>)

    fun getImageBytes(id: String, email: String): Flow<Resource<ByteArray>>

    fun deleteImages(ids: List<String>, email: String): Flow<Resource<Int>>

    fun checkServerHealth(): Flow<Resource<Boolean>>

    fun saveToDownloads(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String
    ): Result<Uri>
}

enum class QueuedUploadWorkResult {
    FINISHED,
    RETRY
}

class ImageRepositoryImpl(
    private val preferences: AppPreferences,
    private val networkClient: NetworkClient,
    private val imageDao: ImageDao,
    private val imageDatabase: ImageDatabase,
    private val uploadScheduler: UploadWorkScheduler? = null,
    private val applicationContext: Context? = null
) : ImageRepository {

    private val accountLocks = ConcurrentHashMap<String, Mutex>()
    private val queuedUploadProcessingMutex = Mutex()

    override fun observeImages(email: String): Flow<List<ImageMetadata>> =
        imageDao.observeImages(normalizeAccount(email)).map { rows -> rows.map(ImageEntity::toMetadata) }

    override fun observeSyncState(email: String): Flow<ImageSyncStateEntity?> =
        imageDao.observeSyncState(normalizeAccount(email))

    override fun observeStorageUsage(email: String): Flow<StorageUsageEntity?> =
        imageDao.observeStorageUsage(normalizeAccount(email))

    override fun observeQueuedUploads(email: String): Flow<List<QueuedUploadEntity>> =
        imageDao.observeQueuedUploads(normalizeAccount(email))

    override fun observePendingUploadCount(email: String): Flow<Int> =
        imageDao.observePendingUploadCount(normalizeAccount(email))

    override suspend fun queueUpload(
        context: Context,
        uri: Uri,
        email: String,
        format: OutputFormat
    ): Resource<QueuedUploadEntity> = withContext(Dispatchers.IO) {
        val account = normalizeAccount(email)
        if (account.isBlank()) {
            return@withContext Resource.Error("Sign in before queuing an image upload.")
        }
        if (!ValidationUtil.isValidGmail(account)) {
            return@withContext Resource.Error("The signed-in account is not valid.")
        }
        val scheduler = uploadScheduler
            ?: return@withContext Resource.Error("Background upload scheduling is unavailable.")

        val operationId = UUID.randomUUID().toString()
        val fileStore = PendingUploadFileStore(context)
        val stagedFile: StagedUploadFile
        try {
            stagedFile = fileStore.stage(uri, operationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Resource.Error(
                e.message ?: "Could not save this image for background upload.",
                e
            )
        }

        val now = System.currentTimeMillis()
        val upload = QueuedUploadEntity(
            operationId = operationId,
            accountScope = account,
            localFilePath = stagedFile.path,
            originalFilename = stagedFile.filename,
            mimeType = stagedFile.mimeType,
            fileSizeBytes = stagedFile.sizeBytes,
            outputFormat = format.apiValue,
            status = UploadQueueStatus.QUEUED,
            attempts = 0,
            createdAt = now,
            updatedAt = now
        )

        try {
            imageDatabase.withTransaction {
                imageDao.insertQueuedUpload(upload)
            }
        } catch (e: CancellationException) {
            fileStore.delete(stagedFile.path)
            throw e
        } catch (e: Exception) {
            fileStore.delete(stagedFile.path)
            return@withContext Resource.Error(
                "Could not save this image to the upload queue. Please try again.",
                e
            )
        }

        try {
            scheduler.enqueue(operationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            imageDao.setQueuedUploadFailure(
                operationId,
                account,
                UploadQueueStatus.RETRYABLE_FAILURE,
                "WORK_SCHEDULING_FAILED",
                "Background upload could not be scheduled. Retry this item from the upload queue.",
                System.currentTimeMillis()
            )
            return@withContext Resource.Error(
                "The image is saved on this device, but background upload could not be scheduled. Retry it from the upload queue.",
                e
            )
        }
        Resource.Success(upload)
    }

    override suspend fun retryQueuedUpload(
        operationId: String,
        email: String
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        val account = normalizeAccount(email)
        val current = imageDao.getQueuedUpload(operationId)
        if (account.isBlank() || current?.accountScope != account) {
            return@withContext Resource.Error("This upload does not belong to the active account.")
        }
        if (current.localFilePath == null) {
            return@withContext Resource.Error("The queued image is no longer available on this device.")
        }
        val scheduler = uploadScheduler
            ?: return@withContext Resource.Error("Background upload scheduling is unavailable.")
        val changed = imageDao.retryQueuedUpload(
            operationId,
            account,
            System.currentTimeMillis()
        )
        if (changed == 0) {
            return@withContext Resource.Error("This upload cannot be retried while it is active.")
        }
        try {
            scheduler.enqueue(operationId)
            Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error(
                "The upload is ready, but background scheduling failed. Reopen the app to resume it.",
                e
            )
        }
    }

    override suspend fun cancelQueuedUpload(
        operationId: String,
        email: String
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        val account = normalizeAccount(email)
        val scheduler = uploadScheduler
            ?: return@withContext Resource.Error("Background upload scheduling is unavailable.")
        if (account.isBlank()) {
            return@withContext Resource.Error("Sign in to manage this upload.")
        }

        val filePath = imageDao.cancelQueuedUpload(
            operationId,
            account,
            System.currentTimeMillis()
        ) ?: return@withContext Resource.Error(
            "This upload is active or no longer available. Active uploads cannot be cancelled."
        )

        try {
            scheduler.cancel(operationId)
            PendingUploadFileStore(
                applicationContext ?: throw IllegalStateException("Application context is unavailable.")
            ).delete(filePath)
            Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("The upload was cancelled, but its local file could not be removed.", e)
        }
    }

    override suspend fun removeQueuedUpload(
        operationId: String,
        email: String
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        val account = normalizeAccount(email)
        val upload = imageDao.getQueuedUpload(operationId)
        if (account.isBlank() || upload?.accountScope != account) {
            return@withContext Resource.Error("This upload does not belong to the active account.")
        }
        if (upload.status == UploadQueueStatus.UPLOADING) {
            return@withContext Resource.Error("An active upload cannot be removed.")
        }
        val removed = imageDao.deleteQueuedUpload(operationId, account)
        if (removed == 0) {
            return@withContext Resource.Error("This upload could not be removed.")
        }
        try {
            PendingUploadFileStore(
                applicationContext ?: throw IllegalStateException("Application context is unavailable.")
            ).delete(upload.localFilePath)
            uploadScheduler?.cancel(operationId)
            Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("The queue entry was removed, but local cleanup could not finish.", e)
        }
    }

    override suspend fun resumeQueuedUploads(email: String) = withContext(Dispatchers.IO) {
        val account = normalizeAccount(email)
        val scheduler = uploadScheduler ?: return@withContext
        if (account.isBlank()) return@withContext
        if (
            !preferences.isLoggedInFlow.first() ||
            normalizeAccount(preferences.userEmailFlow.first().orEmpty()) != account
        )
            return@withContext

        imageDao.recoverInterruptedUploads(account, System.currentTimeMillis())
        val uploads = imageDao.getAllQueuedUploads()
            .filter { it.accountScope == account }
        uploads.forEach { upload ->
            when (upload.status) {
                UploadQueueStatus.QUEUED,
                UploadQueueStatus.RETRYABLE_FAILURE,
                UploadQueueStatus.UPLOADING -> scheduler.enqueue(upload.operationId)
                UploadQueueStatus.WAITING_FOR_ACCOUNT -> {
                    if (
                        imageDao.retryQueuedUpload(
                            upload.operationId,
                            account,
                            System.currentTimeMillis()
                        ) == 1
                    )
                        scheduler.enqueue(upload.operationId)
                }
            }
        }
    }

    override suspend fun cleanupQueuedUploadFiles() = withContext(Dispatchers.IO) {
        val context = applicationContext
            ?: throw IllegalStateException("Application context is unavailable.")
        val fileStore = PendingUploadFileStore(context)
        val uploads = imageDao.getAllQueuedUploads()
        uploads
            .filter {
                it.status != UploadQueueStatus.COMPLETED &&
                    it.status != UploadQueueStatus.CANCELLED
            }
            .filterNot { fileStore.isAvailable(it.localFilePath) }
            .forEach { upload ->
                imageDao.setQueuedUploadFailure(
                    upload.operationId,
                    upload.accountScope,
                    UploadQueueStatus.PERMANENT_FAILURE,
                    "LOCAL_FILE_MISSING",
                    "The queued image is unavailable on this device. Remove this item and choose the image again.",
                    System.currentTimeMillis()
                )
            }
        val referencedPaths = uploads
            .filter {
                it.status != UploadQueueStatus.COMPLETED &&
                    it.status != UploadQueueStatus.CANCELLED &&
                    fileStore.isAvailable(it.localFilePath)
            }
            .mapNotNull(QueuedUploadEntity::localFilePath)
            .toSet()
        fileStore.cleanupOrphans(referencedPaths)
    }

    override suspend fun processQueuedUpload(
        operationId: String
    ): QueuedUploadWorkResult = queuedUploadProcessingMutex.withLock {
        processQueuedUploadInternal(operationId)
    }

    private suspend fun processQueuedUploadInternal(
        operationId: String
    ): QueuedUploadWorkResult = withContext(Dispatchers.IO) {
        val context = applicationContext
            ?: throw IllegalStateException("Application context is unavailable.")
        val fileStore = PendingUploadFileStore(context)
        val upload = imageDao.getQueuedUpload(operationId)
            ?: return@withContext QueuedUploadWorkResult.FINISHED
        if (
            upload.status == UploadQueueStatus.COMPLETED ||
            upload.status == UploadQueueStatus.CANCELLED
        )
            return@withContext QueuedUploadWorkResult.FINISHED

        val signedIn = preferences.isLoggedInFlow.first()
        val activeAccount = normalizeAccount(preferences.userEmailFlow.first().orEmpty())
        if (!signedIn || activeAccount != upload.accountScope) {
            imageDao.pauseQueuedUploadForAccount(
                operationId,
                upload.accountScope,
                "Sign in to ${upload.accountScope} to resume this upload.",
                System.currentTimeMillis()
            )
            return@withContext QueuedUploadWorkResult.FINISHED
        }

        val authorization = try {
            authorizationForAccount(upload.accountScope)
        } catch (e: AuthSessionException) {
            imageDao.pauseQueuedUploadForAccount(
                operationId,
                upload.accountScope,
                e.message ?: "Sign in again to resume this upload.",
                System.currentTimeMillis()
            )
            return@withContext QueuedUploadWorkResult.FINISHED
        }

        val filePath = upload.localFilePath
        if (filePath == null) {
            imageDao.setQueuedUploadFailure(
                operationId,
                upload.accountScope,
                UploadQueueStatus.PERMANENT_FAILURE,
                "LOCAL_FILE_MISSING",
                "The queued image is missing. Remove this item and choose the image again.",
                System.currentTimeMillis()
            )
            return@withContext QueuedUploadWorkResult.FINISHED
        }

        val imageFile = try {
            fileStore.fileForRead(filePath)
        } catch (e: IOException) {
            imageDao.setQueuedUploadFailure(
                operationId,
                upload.accountScope,
                UploadQueueStatus.PERMANENT_FAILURE,
                "LOCAL_FILE_INVALID",
                e.message ?: "The queued image is unavailable. Choose it again.",
                System.currentTimeMillis()
            )
            return@withContext QueuedUploadWorkResult.FINISHED
        }
        if (!imageFile.isFile || !imageFile.canRead() || imageFile.length() != upload.fileSizeBytes) {
            imageDao.setQueuedUploadFailure(
                operationId,
                upload.accountScope,
                UploadQueueStatus.PERMANENT_FAILURE,
                "LOCAL_FILE_MISSING",
                "The queued image is missing or changed. Remove it and choose the image again.",
                System.currentTimeMillis()
            )
            return@withContext QueuedUploadWorkResult.FINISHED
        }

        val format = OutputFormat.entries.firstOrNull { it.apiValue == upload.outputFormat }
        if (format == null) {
            imageDao.setQueuedUploadFailure(
                operationId,
                upload.accountScope,
                UploadQueueStatus.PERMANENT_FAILURE,
                "INVALID_OUTPUT_FORMAT",
                "The saved output format is no longer supported.",
                System.currentTimeMillis()
            )
            return@withContext QueuedUploadWorkResult.FINISHED
        }

        if (
            imageDao.beginQueuedUpload(
                operationId,
                upload.accountScope,
                System.currentTimeMillis()
            ) == 0
        )
            return@withContext QueuedUploadWorkResult.FINISHED

        var requestStarted = false
        var uploadConfirmed = false
        try {
            val api = apiService()
            val usageResult = refreshStorageUsage(upload.accountScope, forceRefresh = true)
            when (usageResult) {
                is Resource.Error -> {
                    if (usageResult.statusCode == 401) {
                        imageDao.pauseQueuedUploadForAccount(
                            operationId,
                            upload.accountScope,
                            usageResult.message,
                            System.currentTimeMillis()
                        )
                        return@withContext QueuedUploadWorkResult.FINISHED
                    }
                    return@withContext recordUploadFailure(
                        upload,
                        usageResult.message,
                        usageResult.statusCode,
                        usageResult.errorCode,
                        usageResult.cause,
                        operationId
                    )
                }
                is Resource.Success -> if (usageResult.data.idempotencySupported != true) {
                    imageDao.setQueuedUploadFailure(
                        operationId,
                        upload.accountScope,
                        UploadQueueStatus.PERMANENT_FAILURE,
                        "SERVER_IDEMPOTENCY_REQUIRED",
                        "Update the server before uploading queued images. Safe automatic retries are not supported by this server.",
                        System.currentTimeMillis()
                    )
                    return@withContext QueuedUploadWorkResult.FINISHED
                }
                Resource.Loading -> error("Storage usage refresh returned an invalid loading state.")
            }

            val part = FileUtils.fileToMultipartPart(
                imageFile,
                upload.originalFilename,
                upload.mimeType
            )
            requestStarted = true
            val response = api.uploadImage(
                authorization = authorization,
                idempotencyKey = operationId,
                image = part,
                format = format.apiValue.toRequestBody("text/plain".toMediaTypeOrNull())
            )
            if (!response.isSuccessful) {
                val details = NetworkErrorHandler.parseErrorDetails(response)
                if (response.code() == 401) {
                    imageDao.pauseQueuedUploadForAccount(
                        operationId,
                        upload.accountScope,
                        details.message,
                        System.currentTimeMillis()
                    )
                    return@withContext QueuedUploadWorkResult.FINISHED
                }
                val outcome = recordUploadFailure(
                    upload,
                    details.message,
                    response.code(),
                    details.errorCode,
                    null,
                    operationId
                )
                if (details.errorCode == STORAGE_QUOTA_EXCEEDED)
                    refreshStorageUsage(upload.accountScope, forceRefresh = true)
                return@withContext outcome
            }

            uploadConfirmed = true
            val metadata = response.body()?.data
            val entity = metadata?.toEntity(upload.accountScope, System.currentTimeMillis())
            if (metadata == null || entity == null) {
                return@withContext recordUploadFailure(
                    upload,
                    "The server response did not contain valid image metadata. The same upload will be retried safely.",
                    null,
                    "UPLOAD_RESPONSE_INVALID",
                    IOException("Missing image metadata in successful upload response."),
                    operationId
                )
            }
            val savedPath = imageDao.completeQueuedUpload(
                operationId,
                upload.accountScope,
                entity,
                System.currentTimeMillis()
            )
            try {
                fileStore.delete(savedPath)
                imageDao.clearCompletedUploadFilePath(
                    operationId,
                    upload.accountScope,
                    System.currentTimeMillis()
                )
            } catch (e: IOException) {
                android.util.Log.e(
                    "ImageRepository",
                    "Uploaded image was confirmed, but local queue file cleanup failed.",
                    e
                )
            }
            refreshStorageUsage(upload.accountScope, forceRefresh = true)
            QueuedUploadWorkResult.FINISHED
        } catch (e: AuthSessionException) {
            imageDao.pauseQueuedUploadForAccount(
                operationId,
                upload.accountScope,
                e.message ?: "Sign in again to resume this upload.",
                System.currentTimeMillis()
            )
            QueuedUploadWorkResult.FINISHED
        } catch (e: CancellationException) {
            imageDao.recoverInterruptedUpload(
                operationId,
                upload.accountScope,
                System.currentTimeMillis()
            )
            throw e
        } catch (e: IOException) {
            if (!requestStarted) {
                imageDao.setQueuedUploadFailure(
                    operationId,
                    upload.accountScope,
                    UploadQueueStatus.PERMANENT_FAILURE,
                    "LOCAL_FILE_UNREADABLE",
                    e.message ?: "The queued image cannot be read. Remove it and choose the image again.",
                    System.currentTimeMillis()
                )
                return@withContext QueuedUploadWorkResult.FINISHED
            }
            recordUploadFailure(
                upload,
                NetworkErrorHandler.parseException(e),
                null,
                "NETWORK_ERROR",
                e,
                operationId
            )
        } catch (e: Exception) {
            if (uploadConfirmed) {
                return@withContext recordUploadFailure(
                    upload,
                    "The server accepted the image, but this device could not save the confirmation. It will safely retry the same operation.",
                    null,
                    "LOCAL_PERSISTENCE_FAILED",
                    e,
                    operationId
                )
            }
            imageDao.setQueuedUploadFailure(
                operationId,
                upload.accountScope,
                UploadQueueStatus.PERMANENT_FAILURE,
                "UPLOAD_FAILED",
                e.message ?: "The upload could not be completed. Review the image and retry.",
                System.currentTimeMillis()
            )
            QueuedUploadWorkResult.FINISHED
        }
    }

    private suspend fun recordUploadFailure(
        upload: QueuedUploadEntity,
        message: String,
        statusCode: Int?,
        errorCode: String?,
        cause: Throwable?,
        operationId: String
    ): QueuedUploadWorkResult {
        if (statusCode == 401) {
            imageDao.pauseQueuedUploadForAccount(
                operationId,
                upload.accountScope,
                message,
                System.currentTimeMillis()
            )
            return QueuedUploadWorkResult.FINISHED
        }

        val isQuotaExceeded = errorCode == STORAGE_QUOTA_EXCEEDED
        val isRetryable = !isQuotaExceeded && (
            statusCode in RETRYABLE_HTTP_STATUSES ||
                (statusCode != null && statusCode >= 500) ||
                (statusCode == null && cause is IOException)
            )
        val status = if (isRetryable) {
            UploadQueueStatus.RETRYABLE_FAILURE
        } else {
            UploadQueueStatus.PERMANENT_FAILURE
        }
        imageDao.setQueuedUploadFailure(
            operationId,
            upload.accountScope,
            status,
            errorCode ?: if (isRetryable) "UPLOAD_TEMPORARILY_UNAVAILABLE" else "UPLOAD_REJECTED",
            message,
            System.currentTimeMillis()
        )
        return if (isRetryable) QueuedUploadWorkResult.RETRY else QueuedUploadWorkResult.FINISHED
    }

    override suspend fun refreshStorageUsage(
        email: String,
        forceRefresh: Boolean
    ): Resource<StorageUsage> {
        val account = normalizeAccount(email)
        if (account.isBlank())
            return Resource.Error("A signed-in account is required to load storage usage.")

        return accountLock(account).withLock {
            val previous = imageDao.getStorageUsage(account)
            val now = System.currentTimeMillis()
            if (
                !forceRefresh &&
                previous != null &&
                previous.lastSyncError == null &&
                now - previous.fetchedAt < SYNC_FRESHNESS_MILLIS
            )
                return@withLock Resource.Success(previous.toStorageUsage())

            try {
                val authorization = authorizationForAccount(account)
                val response = apiService().getStorageUsage(authorization)
                if (!response.isSuccessful) {
                    val details = NetworkErrorHandler.parseErrorDetails(response)
                    persistStorageUsageError(account, previous, details.message)
                    return@withLock Resource.Error(
                        details.message,
                        statusCode = response.code(),
                        errorCode = details.errorCode
                    )
                }

                val usage = response.body()?.data
                    ?: throw IllegalStateException("The server returned no storage usage data.")
                validateStorageUsage(usage)
                val entity = usage.toEntity(account, now)
                imageDao.upsertStorageUsage(entity)
                Resource.Success(usage)
            } catch (e: AuthSessionException) {
                val message = e.message ?: "Please sign in again."
                persistStorageUsageError(account, previous, message)
                Resource.Error(message, e, 401)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = NetworkErrorHandler.parseException(e)
                persistStorageUsageError(account, previous, message)
                Resource.Error(message, e)
            }
        }
    }

    override suspend fun synchronizeImages(
        email: String,
        forceRefresh: Boolean
    ): Resource<Unit> {
        val account = normalizeAccount(email)
        if (account.isBlank()) return Resource.Error("A signed-in account is required to sync images.")

        return accountLock(account).withLock {
            val now = System.currentTimeMillis()
            val previousState = imageDao.getSyncState(account)
            val lastSync = previousState?.lastSuccessfulSyncAt
            if (!forceRefresh && lastSync != null && now - lastSync < SYNC_FRESHNESS_MILLIS) {
                return@withLock Resource.Success(Unit)
            }

            try {
                val authorization = authorizationForAccount(account)
                val api = apiService()
                val metadata = fetchServerImages(api, authorization)
                val entities = metadata.mapNotNull { it.toEntity(account, now) }

                imageDatabase.withTransaction {
                    imageDao.replaceAccountImages(account, entities)
                    imageDao.upsertSyncState(
                        ImageSyncStateEntity(
                            accountScope = account,
                            lastSuccessfulSyncAt = now,
                            lastSyncError = null
                        )
                    )
                }
                Resource.Success(Unit)
            } catch (e: AuthSessionException) {
                persistSyncError(account, previousState, e.message ?: "Please sign in again.")
                Resource.Error(e.message ?: "Please sign in again.", e, 401)
            } catch (e: RemoteImageException) {
                persistSyncError(account, previousState, e.message ?: "Image sync failed.")
                Resource.Error(e.message ?: "Image sync failed.", e, e.statusCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = NetworkErrorHandler.parseException(e)
                persistSyncError(account, previousState, message)
                Resource.Error(message, e)
            }
        }
    }

    override fun uploadImage(
        context: Context,
        uri: Uri,
        email: String,
        format: OutputFormat
    ): Flow<Resource<ImageMetadata>> = flow {
        var refreshUsage = false
        emit(Resource.Loading)
        val account = normalizeAccount(email)
        if (account.isBlank()) {
            emit(Resource.Error("A valid account is required to upload an image."))
            return@flow
        }

        val size = FileUtils.getFileSize(context, uri)
        if (size > FileUtils.MAX_IMAGE_BYTES) {
            emit(Resource.Error("Selected image is larger than 10 MiB limit ($size bytes)."))
            return@flow
        }

        val part = FileUtils.uriToMultipartPart(context, uri)
        if (part == null) {
            emit(Resource.Error("Could not read image file from device storage."))
            return@flow
        }

        accountLock(account).withLock {
            try {
                val authorization = authorizationForAccount(account)
                val api = apiService()
                val response = api.uploadImage(
                    authorization = authorization,
                    image = part,
                    format = format.apiValue.toRequestBody("text/plain".toMediaTypeOrNull())
                )

                if (!response.isSuccessful) {
                    val details = NetworkErrorHandler.parseErrorDetails(response)
                    emit(
                        Resource.Error(
                            details.message,
                            statusCode = response.code(),
                            errorCode = details.errorCode
                        )
                    )
                    return@withLock
                }

                val metadata = response.body()?.data
                if (metadata == null || metadata.effectiveId.isBlank()) {
                    emit(Resource.Error("The server reported upload success without valid image metadata."))
                    return@withLock
                }

                val uploadedAt = System.currentTimeMillis()
                val entity = metadata.toEntity(account, uploadedAt)
                if (entity == null) {
                    emit(Resource.Error("The server response did not contain a valid image ID."))
                    return@withLock
                }
                imageDao.upsertImages(listOf(entity))
                refreshUsage = true
                emit(Resource.Success(metadata))
            } catch (e: AuthSessionException) {
                emit(Resource.Error(e.message ?: "Please sign in again.", e, 401))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is java.io.IOException) {
                    "Upload outcome is unknown because the connection was interrupted. Refresh My Images before retrying."
                } else {
                    NetworkErrorHandler.parseException(e)
                }
                emit(Resource.Error(message, e))
            }
        }
        if (refreshUsage) refreshStorageUsage(account, forceRefresh = true)
    }.flowOn(Dispatchers.IO)

    override fun getImageBytes(id: String, email: String): Flow<Resource<ByteArray>> = flow {
        emit(Resource.Loading)
        if (id.isBlank()) {
            emit(Resource.Error("A valid image ID is required."))
            return@flow
        }

        try {
            val account = normalizeAccount(email)
            val authorization = authorizationForAccount(account)
            val response = apiService().getImageBytes(authorization, id)
            if (response.isSuccessful) {
                val bytes = response.body()?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    emit(Resource.Success(bytes))
                } else {
                    emit(Resource.Error("Empty image stream received from server.", statusCode = response.code()))
                }
            } else {
                emit(
                    Resource.Error(
                        NetworkErrorHandler.parseError(response),
                        statusCode = response.code()
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthSessionException) {
            emit(Resource.Error(e.message ?: "Please sign in again.", e, 401))
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun removeCachedImages(email: String, ids: List<String>) {
        val account = normalizeAccount(email)
        val validIds = ids.filter(String::isNotBlank).distinct()
        if (account.isNotBlank() && validIds.isNotEmpty()) {
            imageDao.deleteImages(account, validIds)
        }
    }

    override fun deleteImages(ids: List<String>, email: String): Flow<Resource<Int>> = flow {
        var refreshUsage = false
        emit(Resource.Loading)
        val validIds = ids.filter(String::isNotBlank).distinct()
        if (validIds.isEmpty()) {
            emit(Resource.Error("At least one valid image ID is required."))
            return@flow
        }

        val account = normalizeAccount(email)
        if (account.isBlank()) {
            emit(Resource.Error("A signed-in account is required to delete images."))
            return@flow
        }

        accountLock(account).withLock {
            try {
                val authorization = authorizationForAccount(account)
                val api = apiService()
                val response = api.deleteImages(
                    authorization,
                    com.example.data.model.DeleteImagesRequest(ids = validIds)
                )
                if (!response.isSuccessful) {
                    if (response.code() == 404 && isImageNotFound(response.errorBody()?.string())) {
                        imageDao.deleteImages(account, validIds)
                        refreshUsage = true
                        emit(Resource.Success(0))
                    } else {
                        emit(Resource.Error(NetworkErrorHandler.parseError(response), statusCode = response.code()))
                    }
                    return@withLock
                }

                val deletedCount = response.body()?.data
                if (deletedCount == null || deletedCount !in 0..validIds.size) {
                    emit(Resource.Error("The server returned an invalid delete response."))
                    return@withLock
                }

                if (deletedCount == validIds.size) {
                    imageDao.deleteImages(account, validIds)
                    refreshUsage = true
                } else {
                    val remainingRemoteImages = fetchServerImages(api, authorization)
                    val remainingIds = remainingRemoteImages.mapTo(HashSet()) { it.effectiveId }
                    val confirmedMissing = validIds.filterNot(remainingIds::contains)
                    imageDatabase.withTransaction {
                        imageDao.deleteImages(account, confirmedMissing)
                        val now = System.currentTimeMillis()
                        imageDao.replaceAccountImages(
                            account,
                            remainingRemoteImages.mapNotNull { it.toEntity(account, now) }
                        )
                        imageDao.upsertSyncState(
                            ImageSyncStateEntity(
                                accountScope = account,
                                lastSuccessfulSyncAt = now,
                                lastSyncError = null
                            )
                        )
                    }
                    if (confirmedMissing.size != validIds.size) {
                        emit(Resource.Error("Some selected images could not be deleted."))
                        return@withLock
                    }
                    refreshUsage = true
                }
                emit(Resource.Success(deletedCount))
            } catch (e: AuthSessionException) {
                emit(Resource.Error(e.message ?: "Please sign in again.", e, 401))
            } catch (e: RemoteImageException) {
                emit(Resource.Error(e.message ?: "Could not verify image deletion.", e, e.statusCode))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
            }
        }
        if (refreshUsage) refreshStorageUsage(account, forceRefresh = true)
    }.flowOn(Dispatchers.IO)

    override fun checkServerHealth(): Flow<Resource<Boolean>> = flow {
        emit(Resource.Loading)
        try {
            val response = apiService().checkHealth()
            if (response.isSuccessful) {
                emit(Resource.Success(true))
            } else {
                emit(Resource.Error("Server returned HTTP ${response.code()}", statusCode = response.code()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    override fun saveToDownloads(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String
    ): Result<Uri> = FileUtils.saveBytesToDownloads(context, bytes, filename, mimeType)

    private suspend fun fetchServerImages(api: ApiService, authorization: String): List<ImageMetadata> {
        val response = api.getImages(authorization)
        if (response.isSuccessful) {
            val body = response.body()
                ?: throw RemoteImageException("The server returned an empty image-list response.")
            val images = body.data
                ?: throw RemoteImageException("The server response did not contain image metadata.")
            val ids = images.map(ImageMetadata::effectiveId)
            if (ids.any(String::isBlank) || ids.toSet().size != ids.size) {
                throw RemoteImageException("The server returned invalid or duplicate image IDs.")
            }
            return images
        }

        if (response.code() == 404) {
            val errorBody = response.errorBody()?.string()
            if (isImageNotFound(errorBody)) return emptyList()
            throw RemoteImageException("The server returned an unrecognized 404 for the image list.", 404)
        }

        throw RemoteImageException(
            NetworkErrorHandler.parseError(response),
            response.code()
        )
    }

    private suspend fun persistSyncError(
        account: String,
        previousState: ImageSyncStateEntity?,
        message: String
    ) {
        imageDao.upsertSyncState(
            ImageSyncStateEntity(
                accountScope = account,
                lastSuccessfulSyncAt = previousState?.lastSuccessfulSyncAt,
                lastSyncError = message
            )
        )
    }

    private suspend fun apiService(): ApiService {
        val baseUrl = preferences.baseUrlFlow.first()
        return networkClient.getApiService(baseUrl)
    }

    private suspend fun persistStorageUsageError(
        account: String,
        previous: StorageUsageEntity?,
        message: String
    ) {
        if (previous != null) {
            imageDao.upsertStorageUsage(previous.copy(lastSyncError = message))
        }
    }

    private fun validateStorageUsage(usage: StorageUsage) {
        if (
            usage.usedBytes < 0 ||
            usage.quotaBytes <= 0 ||
            usage.availableBytes != (usage.quotaBytes - usage.usedBytes).coerceAtLeast(0) ||
            usage.usagePercentage !in 0..100 ||
            usage.imageCount < 0 ||
            usage.warningLevel !in setOf("normal", "info", "warning", "full")
        ) {
            throw IllegalStateException("The server returned invalid storage usage values.")
        }
    }

    private suspend fun authorizationForAccount(account: String): String {
        val currentAccount = normalizeAccount(preferences.userEmailFlow.first().orEmpty())
        val accessToken = preferences.accessTokenFlow.first()
        if (
            currentAccount.isBlank() ||
            currentAccount != account ||
            accessToken.isNullOrBlank() ||
            !preferences.isLoggedInFlow.first()
        ) {
            throw AuthSessionException("Your session is unavailable or has expired. Please sign in again.")
        }

        return "Bearer $accessToken"
    }

    private fun accountLock(account: String): Mutex =
        accountLocks.computeIfAbsent(account) { Mutex() }

    private fun normalizeAccount(email: String): String =
        email.trim().lowercase(Locale.ROOT)

    private fun isImageNotFound(body: String?): Boolean = try {
        body?.let { JSONObject(it).optString("message") == IMAGE_NOT_FOUND_MESSAGE } == true
    } catch (_: Exception) {
        false
    }

    private class RemoteImageException(
        message: String,
        val statusCode: Int? = null
    ) : Exception(message)

    private class AuthSessionException(message: String) : Exception(message)

    private companion object {
        const val SYNC_FRESHNESS_MILLIS = 2 * 60 * 1000L
        const val IMAGE_NOT_FOUND_MESSAGE = "Image not found"
        const val STORAGE_QUOTA_EXCEEDED = "STORAGE_QUOTA_EXCEEDED"
        val RETRYABLE_HTTP_STATUSES = setOf(408, 425, 429)
    }
}
