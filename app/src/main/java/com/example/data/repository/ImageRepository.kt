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
import com.example.data.local.StorageUsageEntity
import com.example.data.local.toEntity
import com.example.data.model.ImageMetadata
import com.example.data.model.OutputFormat
import com.example.data.model.StorageUsage
import com.example.data.preferences.AppPreferences
import com.example.util.FileUtils
import com.example.util.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

class ImageRepositoryImpl(
    private val preferences: AppPreferences,
    private val networkClient: NetworkClient,
    private val imageDao: ImageDao,
    private val imageDatabase: ImageDatabase
) : ImageRepository {

    private val accountLocks = ConcurrentHashMap<String, Mutex>()

    override fun observeImages(email: String): Flow<List<ImageMetadata>> =
        imageDao.observeImages(normalizeAccount(email)).map { rows -> rows.map(ImageEntity::toMetadata) }

    override fun observeSyncState(email: String): Flow<ImageSyncStateEntity?> =
        imageDao.observeSyncState(normalizeAccount(email))

    override fun observeStorageUsage(email: String): Flow<StorageUsageEntity?> =
        imageDao.observeStorageUsage(normalizeAccount(email))

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
    }
}
