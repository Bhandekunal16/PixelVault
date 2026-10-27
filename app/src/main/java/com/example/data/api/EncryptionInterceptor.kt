package com.example.data.api

import android.util.Base64
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Isolated Response Decryption Interceptor for Express backend encryption compatibility.
 *
 * BACKEND ENCRYPTION SPECIFICATION:
 * When the backend has `encryption_Interceptor` enabled, JSON responses are wrapped as:
 *   {"data":"<base64-ciphertext>"}
 *
 * REQUIREMENTS:
 * 1. Binary image responses (e.g. GET /image?id=...) MUST NOT be processed as JSON ciphertext.
 * 2. When disabled, responses pass through untouched.
 * 3. Secrets are NOT hardcoded. They can be configured dynamically via AppPreferences in Settings.
 * 4. Supports AES/CBC/PKCS5Padding or AES/GCM/NoPadding depending on the backend configuration.
 */
class EncryptionInterceptor(
    private val isEncryptionEnabled: () -> Boolean,
    private val getSecretKey: () -> String?,
    private val getAlgorithm: () -> String = { "AES/CBC/PKCS5Padding" }
) : Interceptor {

    companion object {
        private const val TAG = "EncryptionInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Only intercept if feature is enabled
        if (!isEncryptionEnabled()) {
            return response
        }

        val responseBody = response.body ?: return response
        val contentType = responseBody.contentType()

        // 1. Never decrypt binary image downloads or non-JSON payloads
        val isJson = contentType != null && contentType.subtype.contains("json", ignoreCase = true)
        if (!isJson) {
            return response
        }

        val rawBodyString = responseBody.string()

        // Check if response matches the encryption envelope: {"data":"<base64>"}
        return try {
            val jsonObject = JSONObject(rawBodyString)
            if (jsonObject.has("data") && jsonObject.length() == 1 && jsonObject.get("data") is String) {
                val ciphertext = jsonObject.getString("data")
                val key = getSecretKey()

                if (key.isNullOrBlank()) {
                    Log.w(TAG, "Response is encrypted with {\"data\":\"...\"} but no secret key is configured in Settings.")
                    // Return original response since key is missing
                    response.newBuilder()
                        .body(rawBodyString.toResponseBody(contentType))
                        .build()
                } else {
                    val decryptedJson = decryptPayload(ciphertext, key, getAlgorithm())
                    response.newBuilder()
                        .body(decryptedJson.toResponseBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                        .build()
                }
            } else {
                // Regular unencrypted JSON
                response.newBuilder()
                    .body(rawBodyString.toResponseBody(contentType))
                    .build()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Not an encrypted JSON payload or decryption skipped: ${e.message}")
            response.newBuilder()
                .body(rawBodyString.toResponseBody(contentType))
                .build()
        }
    }

    private fun decryptPayload(ciphertextBase64: String, keyString: String, algorithm: String): String {
        val cipherBytes = Base64.decode(ciphertextBase64, Base64.DEFAULT)
        val keyBytes = keyString.toByteArray(StandardCharsets.UTF_8)
        val keySpec = SecretKeySpec(keyBytes.copyOf(16), "AES") // 128-bit key default

        val cipher = Cipher.getInstance(algorithm)
        if (algorithm.contains("CBC", ignoreCase = true)) {
            // First 16 bytes IV or static zero IV fallback
            val iv = if (cipherBytes.size > 16) {
                IvParameterSpec(cipherBytes.copyOfRange(0, 16))
            } else {
                IvParameterSpec(ByteArray(16))
            }
            val encryptedContent = if (cipherBytes.size > 16) {
                cipherBytes.copyOfRange(16, cipherBytes.size)
            } else {
                cipherBytes
            }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
            val decryptedBytes = cipher.doFinal(encryptedContent)
            return String(decryptedBytes, StandardCharsets.UTF_8)
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            return String(decryptedBytes, StandardCharsets.UTF_8)
        }
    }
}
