package com.example.util

sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val statusCode: Int? = null
    ) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}
