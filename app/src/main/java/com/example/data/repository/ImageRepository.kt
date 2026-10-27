package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.api.NetworkClient
import com.example.data.api.NetworkErrorHandler
import com.example.data.model.DeleteImagesRequest
import com.example.data.model.ImageMetadata
import com.example.data.model.OutputFormat
import com.example.data.preferences.AppPreferences
import com.example.util.FileUtils
import com.example.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

interface ImageRepository {
    fun uploadImage(
        context: Context,
        uri: Uri,
        email: String,
        format: OutputFormat
    ): Flow<Resource<ImageMetadata>>

    fun getImages(email: String): Flow<Resource<List<ImageMetadata>>>

    fun getImageBytes(id: String): Flow<Resource<ByteArray>>

    fun deleteImages(ids: List<String>): Flow<Resource<Int>>

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
    private val networkClient: NetworkClient
) : ImageRepository {

    override fun uploadImage(
        context: Context,
        uri: Uri,
        email: String,
        format: OutputFormat
    ): Flow<Resource<ImageMetadata>> = flow {
        emit(Resource.Loading)

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

        try {
            val baseUrl = preferences.baseUrlFlow.first()
            val api = networkClient.getApiService(baseUrl)

            val emailBody = email.trim().toRequestBody("text/plain".toMediaTypeOrNull())
            val formatBody = format.apiValue.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = api.uploadImage(image = part, email = emailBody, format = formatBody)

            if (response.isSuccessful) {
                val metadata = response.body()?.data
                if (metadata != null) {
                    emit(Resource.Success(metadata))
                } else {
                    emit(Resource.Success(ImageMetadata(filename = FileUtils.getFileName(context, uri), format = format.label)))
                }
            } else {
                emit(Resource.Error(NetworkErrorHandler.parseError(response)))
            }
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Retrieves all image metadata associated with the provided email.
     * Note: A 404 response means no images exist for this email, handled gracefully as empty list.
     */
    override fun getImages(email: String): Flow<Resource<List<ImageMetadata>>> = flow {
        emit(Resource.Loading)
        try {
            val baseUrl = preferences.baseUrlFlow.first()
            val api = networkClient.getApiService(baseUrl)

            val response = api.getImagesByEmail(email = email.trim())

            if (response.isSuccessful) {
                val list = response.body()?.data ?: emptyList()
                emit(Resource.Success(list))
            } else if (response.code() == 404) {
                // User has no uploaded images yet; treat 404 as valid empty list
                emit(Resource.Success(emptyList()))
            } else {
                // Try fallback raw parsing in case the backend returns a raw array or alternative JSON
                val rawResponse = api.getImagesByEmailRaw(email.trim())
                if (rawResponse.isSuccessful) {
                    val jsonStr = rawResponse.body()?.string() ?: ""
                    val parsed = parseImagesJson(jsonStr)
                    emit(Resource.Success(parsed))
                } else if (rawResponse.code() == 404) {
                    emit(Resource.Success(emptyList()))
                } else {
                    emit(Resource.Error(NetworkErrorHandler.parseError(rawResponse)))
                }
            }
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    private fun parseImagesJson(jsonStr: String): List<ImageMetadata> {
        val list = mutableListOf<ImageMetadata>()
        try {
            if (jsonStr.trim().startsWith("[")) {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(jsonToImageMetadata(obj))
                }
            } else if (jsonStr.trim().startsWith("{")) {
                val obj = JSONObject(jsonStr)
                if (obj.has("data") && obj.get("data") is JSONArray) {
                    val array = obj.getJSONArray("data")
                    for (i in 0 until array.length()) {
                        list.add(jsonToImageMetadata(array.getJSONObject(i)))
                    }
                }
            }
        } catch (e: Exception) {
            // Return whatever parsed so far
        }
        return list
    }

    private fun jsonToImageMetadata(obj: JSONObject): ImageMetadata {
        return ImageMetadata(
            id = (obj.optString("id").takeIf { it.isNotBlank() } ?: obj.optString("_id").takeIf { it.isNotBlank() }),
            filename = obj.optString("filename").takeIf { it.isNotBlank() },
            mimetype = obj.optString("mimetype").takeIf { it.isNotBlank() },
            format = obj.optString("format").takeIf { it.isNotBlank() },
            width = if (obj.has("width")) obj.optInt("width") else null,
            height = if (obj.has("height")) obj.optInt("height") else null,
            originalSize = if (obj.has("originalSize")) obj.optLong("originalSize") else null,
            storedSize = if (obj.has("storedSize")) obj.optLong("storedSize") else null,
            isOptimized = if (obj.has("isOptimized")) obj.optBoolean("isOptimized") else null,
            optimized = if (obj.has("optimized")) obj.optBoolean("optimized") else null,
            checksum = obj.optString("checksum").takeIf { it.isNotBlank() },
            createdAt = obj.optString("createdAt").takeIf { it.isNotBlank() },
            uploadDate = obj.optString("uploadDate").takeIf { it.isNotBlank() },
            email = obj.optString("email").takeIf { it.isNotBlank() }
        )
    }

    /**
     * Retrieves original stored image bytes as binary data.
     * The response Content-Type is image stream (not JSON).
     */
    override fun getImageBytes(id: String): Flow<Resource<ByteArray>> = flow {
        emit(Resource.Loading)
        try {
            val baseUrl = preferences.baseUrlFlow.first()
            val api = networkClient.getApiService(baseUrl)
            val response = api.getImageBytes(id)

            if (response.isSuccessful) {
                val bytes = response.body()?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    emit(Resource.Success(bytes))
                } else {
                    emit(Resource.Error("Empty image stream received from server."))
                }
            } else {
                emit(Resource.Error(NetworkErrorHandler.parseError(response)))
            }
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Deletes images by ID.
     *
     * BACKEND LIMITATION NOTE:
     * The backend currently does not require authentication or ownership authorization for deletion.
     * Anyone sending valid IDs can delete records. This backend limitation should be hardened
     * in production by enforcing user ownership checks on the server.
     */
    override fun deleteImages(ids: List<String>): Flow<Resource<Int>> = flow {
        emit(Resource.Loading)
        if (ids.isEmpty()) {
            emit(Resource.Success(0))
            return@flow
        }
        if (ids.size > 100) {
            emit(Resource.Error("Backend supports deleting at most 100 images per request."))
            return@flow
        }
        try {
            val baseUrl = preferences.baseUrlFlow.first()
            val api = networkClient.getApiService(baseUrl)
            val response = api.deleteImages(DeleteImagesRequest(ids = ids))

            if (response.isSuccessful) {
                val deletedCount = response.body()?.data?.deletedCount ?: ids.size
                emit(Resource.Success(deletedCount))
            } else {
                emit(Resource.Error(NetworkErrorHandler.parseError(response)))
            }
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    override fun checkServerHealth(): Flow<Resource<Boolean>> = flow {
        emit(Resource.Loading)
        try {
            val baseUrl = preferences.baseUrlFlow.first()
            val api = networkClient.getApiService(baseUrl)
            val response = api.checkHealth()
            if (response.isSuccessful) {
                emit(Resource.Success(true))
            } else {
                emit(Resource.Error("Server returned HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    override fun saveToDownloads(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String
    ): Result<Uri> {
        return FileUtils.saveBytesToDownloads(context, bytes, filename, mimeType)
    }
}
