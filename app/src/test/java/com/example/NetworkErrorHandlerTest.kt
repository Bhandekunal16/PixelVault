package com.example

import com.example.data.api.NetworkErrorHandler
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkErrorHandlerTest {

    @Test
    fun `413 payload too large error message`() {
        val response = Response.error<Any>(
            413,
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        )
        val message = NetworkErrorHandler.parseError(response)
        assertTrue(message.contains("10 MiB", ignoreCase = true))
    }

    @Test
    fun `404 resource not found error message`() {
        val response = Response.error<Any>(
            404,
            "{}".toResponseBody("application/json".toMediaTypeOrNull())
        )
        val message = NetworkErrorHandler.parseError(response)
        assertTrue(message.contains("not found", ignoreCase = true))
    }

    @Test
    fun `500 server error extracts custom message or friendly fallback`() {
        val responseWithCustomMsg = Response.error<Any>(
            500,
            """{"message":"Database indexing failed"}""".toResponseBody("application/json".toMediaTypeOrNull())
        )
        val message = NetworkErrorHandler.parseError(responseWithCustomMsg)
        assertTrue(message.contains("Database indexing failed"))
    }

    @Test
    fun `502 and 504 proxy errors provide user-friendly explanations`() {
        val resp502 = Response.error<Any>(502, "".toResponseBody(null))
        assertTrue(NetworkErrorHandler.parseError(resp502).contains("Bad Gateway", ignoreCase = true))

        val resp504 = Response.error<Any>(504, "".toResponseBody(null))
        assertTrue(NetworkErrorHandler.parseError(resp504).contains("Gateway timeout", ignoreCase = true))
    }

    @Test
    fun `network exceptions are parsed without exposing stack traces`() {
        val timeoutMsg = NetworkErrorHandler.parseException(SocketTimeoutException("Read timed out"))
        assertTrue(timeoutMsg.contains("timed out", ignoreCase = true))

        val connectMsg = NetworkErrorHandler.parseException(ConnectException("Failed to connect to 10.0.2.2"))
        assertTrue(connectMsg.contains("Cannot connect", ignoreCase = true))
    }
}
