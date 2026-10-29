package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StorageUsage(
    @Json(name = "usedBytes") val usedBytes: Long,
    @Json(name = "quotaBytes") val quotaBytes: Long,
    @Json(name = "availableBytes") val availableBytes: Long,
    @Json(name = "usagePercentage") val usagePercentage: Int,
    @Json(name = "imageCount") val imageCount: Int,
    @Json(name = "warningLevel") val warningLevel: String
)
