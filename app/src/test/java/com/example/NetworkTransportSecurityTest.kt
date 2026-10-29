package com.example

import com.example.data.api.NetworkClient
import com.example.data.api.isAllowedCleartextUrl
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NetworkTransportSecurityTest {
    @Test
    fun `rejects cleartext requests to non-local API hosts`() = runBlocking {
        try {
            NetworkClient()
                .getApiService("http://api.example.invalid/")
                .checkHealth()
            throw AssertionError("A remote cleartext request should be rejected.")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("HTTPS is required"))
        }
    }

    @Test
    fun `allows cleartext only to the configured hosted API host and port`() {
        assertTrue(isAllowedCleartextUrl("http://147.224.251.137:8001/".toHttpUrl()))
        assertTrue(isAllowedCleartextUrl("http://10.0.2.2:8000/".toHttpUrl()))
        assertTrue(isAllowedCleartextUrl("http://localhost:8000/".toHttpUrl()))

        assertFalse(isAllowedCleartextUrl("http://147.224.251.137:8000/".toHttpUrl()))
        assertFalse(isAllowedCleartextUrl("http://example.com:8001/".toHttpUrl()))
    }
}
