package com.example.data.api

import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object NetworkErrorHandler {

    private val MESSAGE_REGEX = Regex("\"(?:message|error)\"\\s*:\\s*\"([^\"]+)\"")

    fun parseError(response: Response<*>): String {
        val code = response.code()
        val errorBody = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }

        // Extract user-friendly message from JSON
        var serverMessage: String? = null
        if (!errorBody.isNullOrBlank()) {
            val match = MESSAGE_REGEX.find(errorBody)
            serverMessage = match?.groupValues?.get(1)
        }

        return when (code) {
            400 -> serverMessage ?: "Validation failed. Please verify your input and try again."
            401 -> serverMessage ?: "Authentication required. Please log in again."
            403 -> serverMessage ?: "Access denied. You do not have permission for this action."
            404 -> serverMessage ?: "Resource not found on server."
            413 -> "Image size exceeds the maximum limit (10 MiB). Please choose a smaller image."
            429 -> "Too many requests. Please wait a moment and try again."
            500 -> serverMessage ?: "Internal server error. The backend encountered an issue processing the request."
            502 -> "Bad Gateway: The reverse proxy could not reach the backend server."
            503 -> "Service temporarily unavailable. Please try again shortly."
            504 -> "Gateway timeout: The server took too long to respond."
            else -> serverMessage ?: "Server responded with status code $code."
        }
    }

    fun parseException(throwable: Throwable): String {
        return when (throwable) {
            is SocketTimeoutException -> "Connection timed out. The server took too long to respond."
            is ConnectException, is UnknownHostException -> "Cannot connect to server. Check your network connection and server URL."
            is IOException -> "Network error: ${throwable.localizedMessage ?: "Connection interrupted"}"
            else -> "An unexpected error occurred. Please try again."
        }
    }
}
