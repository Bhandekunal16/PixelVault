package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ImageMetadata(
    @Json(name = "id") val id: String? = null,
    @Json(name = "_id") val mongoId: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "mimetype") val mimetype: String? = null,
    @Json(name = "format") val format: String? = null,
    @Json(name = "width") val width: Int? = null,
    @Json(name = "height") val height: Int? = null,
    @Json(name = "originalSize") val originalSize: Long? = null,
    @Json(name = "storedSize") val storedSize: Long? = null,
    @Json(name = "isOptimized") val isOptimized: Boolean? = null,
    @Json(name = "optimized") val optimized: Boolean? = null,
    @Json(name = "checksum") val checksum: String? = null,
    @Json(name = "createdAt") val createdAt: String? = null,
    @Json(name = "uploadDate") val uploadDate: String? = null,
    @Json(name = "email") val email: String? = null
) {
    val effectiveId: String
        get() = id ?: mongoId ?: ""

    val effectiveOptimized: Boolean
        get() = isOptimized == true || optimized == true

    val effectiveFormat: String
        get() = format?.uppercase()
            ?: mimetype?.substringAfter('/')?.uppercase()
            ?: "IMG"

    val effectiveDate: String
        get() = uploadDate ?: createdAt ?: ""

    val savingsBytes: Long
        get() {
            val orig = originalSize ?: 0L
            val stored = storedSize ?: orig
            return if (orig > stored) orig - stored else 0L
        }

    val savingsPercentage: Int
        get() {
            val orig = originalSize ?: 0L
            val stored = storedSize ?: orig
            return if (orig > 0 && orig > stored) {
                (((orig - stored).toDouble() / orig.toDouble()) * 100).toInt()
            } else 0
        }
}

@JsonClass(generateAdapter = true)
data class DeleteImagesRequest(
    @Json(name = "ids") val ids: List<String>
)

@JsonClass(generateAdapter = true)
data class DeleteImagesData(
    @Json(name = "deletedCount") val deletedCount: Int? = null,
    @Json(name = "acknowledged") val acknowledged: Boolean? = null
)

/**
 * Encrypted payload wrapper when backend encryption_Interceptor is active:
 * {"data":"<base64-ciphertext>"}
 */
@JsonClass(generateAdapter = true)
data class EncryptedResponseWrapper(
    @Json(name = "data") val data: String
)
