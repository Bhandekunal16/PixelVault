package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.StorageUsage

@Entity(tableName = "storage_usage")
data class StorageUsageEntity(
    @PrimaryKey val accountScope: String,
    val usedBytes: Long,
    val quotaBytes: Long,
    val availableBytes: Long,
    val usagePercentage: Int,
    val imageCount: Int,
    val warningLevel: String,
    val fetchedAt: Long,
    val lastSyncError: String? = null
) {
    fun toStorageUsage() = StorageUsage(
        usedBytes = usedBytes,
        quotaBytes = quotaBytes,
        availableBytes = availableBytes,
        usagePercentage = usagePercentage,
        imageCount = imageCount,
        warningLevel = warningLevel
    )
}

fun StorageUsage.toEntity(accountScope: String, fetchedAt: Long) = StorageUsageEntity(
    accountScope = accountScope,
    usedBytes = usedBytes,
    quotaBytes = quotaBytes,
    availableBytes = availableBytes,
    usagePercentage = usagePercentage,
    imageCount = imageCount,
    warningLevel = warningLevel,
    fetchedAt = fetchedAt
)
