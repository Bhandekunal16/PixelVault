package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.ImageMetadata

@Entity(
    tableName = "images",
    primaryKeys = ["accountScope", "serverId"],
    indices = [Index(value = ["accountScope", "createdAt"])]
)
data class ImageEntity(
    val accountScope: String,
    val serverId: String,
    val filename: String?,
    val mimeType: String?,
    val format: String?,
    val width: Int?,
    val height: Int?,
    val originalSize: Long?,
    val storedSize: Long?,
    val optimized: Boolean?,
    val checksum: String?,
    val createdAt: String?,
    val syncStatus: String = SYNCED,
    val lastSyncedAt: Long
) {
    companion object {
        const val SYNCED = "SYNCED"
    }

    fun toMetadata(): ImageMetadata = ImageMetadata(
        id = serverId,
        filename = filename,
        mimetype = mimeType,
        format = format,
        width = width,
        height = height,
        originalSize = originalSize,
        storedSize = storedSize,
        optimized = optimized,
        checksum = checksum,
        createdAt = createdAt,
        email = accountScope
    )
}

fun ImageMetadata.toEntity(accountScope: String, syncedAt: Long): ImageEntity? {
    val serverId = effectiveId.takeIf(String::isNotBlank) ?: return null
    return ImageEntity(
        accountScope = accountScope,
        serverId = serverId,
        filename = effectiveFilename,
        mimeType = effectiveMimeType,
        format = format,
        width = width,
        height = height,
        originalSize = originalSize,
        storedSize = storedSize,
        optimized = optimized ?: isOptimized,
        checksum = checksum,
        createdAt = effectiveDate.takeIf(String::isNotBlank),
        lastSyncedAt = syncedAt
    )
}

@Entity(tableName = "image_sync_state")
data class ImageSyncStateEntity(
    @PrimaryKey val accountScope: String,
    val lastSuccessfulSyncAt: Long? = null,
    val lastSyncError: String? = null
)
