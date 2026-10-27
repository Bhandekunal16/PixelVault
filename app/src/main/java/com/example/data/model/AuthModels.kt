package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class User(
    @Json(name = "id") val id: String? = null,
    @Json(name = "_id") val mongoId: String? = null,
    @Json(name = "email") val email: String,
    @Json(name = "role") val role: String? = "user",
    @Json(name = "createdAt") val createdAt: String? = null
) {
    val effectiveId: String
        get() = id ?: mongoId ?: ""
}

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class LoginData(
    @Json(name = "user") val user: User? = null
)

@JsonClass(generateAdapter = true)
data class RegisterData(
    @Json(name = "user") val user: User? = null
)
