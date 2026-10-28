package com.example

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.test.core.app.ApplicationProvider
import com.example.security.BiometricAuthManager
import com.example.security.BiometricCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BiometricAuthManagerTest {

    private lateinit var context: Context
    private lateinit var authManager: BiometricAuthManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        authManager = BiometricAuthManager(context)
    }

    @Test
    fun testTranslateErrorCode_userCanceled() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_USER_CANCELED, "User canceled")
        assertEquals("Authentication was cancelled.", message)
    }

    @Test
    fun testTranslateErrorCode_negativeButton() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Negative button")
        assertEquals("Authentication was cancelled.", message)
    }

    @Test
    fun testTranslateErrorCode_lockout() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_LOCKOUT, "")
        assertTrue(message.contains("temporarily locked"))
    }

    @Test
    fun testTranslateErrorCode_permanentLockout() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, "")
        assertTrue(message.contains("PIN or password is verified"))
    }

    @Test
    fun testTranslateErrorCode_noBiometrics() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_NO_BIOMETRICS, "")
        assertTrue(message.contains("No biometrics or device screen lock"))
    }

    @Test
    fun testTranslateErrorCode_hardwareUnavailable() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_HW_UNAVAILABLE, "")
        assertTrue(message.contains("unavailable"))
    }

    @Test
    fun testTranslateErrorCode_timeout() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_TIMEOUT, "")
        assertTrue(message.contains("timed out"))
    }

    @Test
    fun testTranslateErrorCode_securityUpdateRequired() {
        val message = authManager.translateErrorCode(BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED, "")
        assertTrue(message.contains("security update is required"))
    }

    @Test
    fun testTranslateErrorCode_fallbackDefault() {
        val customMsg = "Custom hardware sensor fault"
        val message = authManager.translateErrorCode(999, customMsg)
        assertEquals(customMsg, message)
    }

    @Test
    fun testCapabilityProperties() {
        val ready = BiometricCapability.BiometricReady
        assertTrue(ready.canAuthenticate)
        assertTrue(ready.displayName.contains("Ready"))

        val credOnly = BiometricCapability.DeviceCredentialOnly
        assertTrue(credOnly.canAuthenticate)

        val none = BiometricCapability.NoneEnrolled
        assertFalse(none.canAuthenticate)

        val noHw = BiometricCapability.NoHardware
        assertFalse(noHw.canAuthenticate)

        val unavail = BiometricCapability.HardwareUnavailable
        assertFalse(unavail.canAuthenticate)

        val secUpdate = BiometricCapability.SecurityUpdateRequired
        assertFalse(secUpdate.canAuthenticate)
    }

    @Test
    fun testCreateEnrollmentIntent() {
        val intent = authManager.createEnrollmentIntent()
        assertNotNull(intent)
        assertNotNull(intent.action)
    }
}
