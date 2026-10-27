package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Standard envelope returned by the Express backend.
 * Nested payload is contained in [data].
 */
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "message") val message: String? = null,
    @Json(name = "statusCode") val statusCode: Int? = null,
    @Json(name = "status") val status: Boolean? = false,
    @Json(name = "data") val data: T? = null,
    @Json(name = "error") val error: String? = null
)
