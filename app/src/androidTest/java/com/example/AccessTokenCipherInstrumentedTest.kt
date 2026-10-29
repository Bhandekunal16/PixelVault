package com.example

import com.example.data.preferences.AccessTokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class AccessTokenCipherInstrumentedTest {
    @Test
    fun encryptsAndDecryptsAccessTokensWithAndroidKeystore() {
        val cipher = AccessTokenCipher()
        val token = "instrumentation-access-token"

        val encrypted = cipher.encrypt(token)

        assertNotEquals(token, encrypted)
        assertEquals(token, cipher.decrypt(encrypted))
    }
}
