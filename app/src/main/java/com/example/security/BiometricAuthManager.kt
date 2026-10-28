package com.example.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages device-level biometric and credential authentication adhering strictly to
 * Android security guidelines and official BiometricPrompt APIs.
 *
 * Authentication Priority:
 * 1. Biometric authentication (Strong biometric: Fingerprint, Class 3 Face Unlock)
 * 2. Device Face unlock (automatic if verified and supported by Android system)
 * 3. Device PIN (via DEVICE_CREDENTIAL fallback)
 * 4. Device Password or Pattern (via DEVICE_CREDENTIAL fallback)
 * 5. Fallback guidance to Settings if no credentials or biometrics are configured.
 */
class BiometricAuthManager(private val context: Context) {

    private val isPromptActive = AtomicBoolean(false)

    /**
     * Evaluates device security status and returns the highest level capability.
     */
    fun checkCapability(): BiometricCapability {
        val biometricManager = BiometricManager.from(context)

        // 1. First priority: Check Strong Biometrics (Fingerprint / Class 3 Face)
        val strongBiometricStatus = biometricManager.canAuthenticate(Authenticators.BIOMETRIC_STRONG)
        if (strongBiometricStatus == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricCapability.BiometricReady
        }

        // 2. Next check combined biometric or device credential (PIN / Pattern / Password)
        val combinedAuthenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            // Pre-API 30 check for device credentials
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        }

        val combinedStatus = biometricManager.canAuthenticate(combinedAuthenticators)
        return when (combinedStatus) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                BiometricCapability.DeviceCredentialOnly
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                BiometricCapability.NoneEnrolled
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                BiometricCapability.NoHardware
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                BiometricCapability.HardwareUnavailable
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                BiometricCapability.SecurityUpdateRequired
            }
            else -> {
                // Secondary check specifically for device credential if available
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val credentialStatus = biometricManager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL)
                    if (credentialStatus == BiometricManager.BIOMETRIC_SUCCESS) {
                        return BiometricCapability.DeviceCredentialOnly
                    }
                }
                BiometricCapability.Unknown(combinedStatus)
            }
        }
    }

    /**
     * Determines whether the device has any supported lock method configured.
     */
    fun isDeviceLockConfigured(): Boolean {
        val capability = checkCapability()
        return capability.canAuthenticate
    }

    /**
     * Launches the system BiometricPrompt on the given FragmentActivity.
     * Prevents multiple concurrent authentication prompts from appearing simultaneously.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock ImageVault",
        subtitle: String = "Confirm your identity with biometric or device PIN",
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit,
        onFailed: () -> Unit
    ) {
        if (!isPromptActive.compareAndSet(false, true)) {
            // An authentication prompt is already active on screen
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isPromptActive.set(false)
                onSuccess(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isPromptActive.set(false)
                val friendlyMessage = translateErrorCode(errorCode, errString.toString())
                onError(errorCode, friendlyMessage)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // A biometric was rejected (e.g. unrecognized fingerprint).
                // Prompt stays open for retry until max attempts or user cancels.
                onFailed()
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ supports combining BIOMETRIC_STRONG with DEVICE_CREDENTIAL directly.
            // Note: When DEVICE_CREDENTIAL is used, setNegativeButtonText MUST NOT be called.
            promptInfoBuilder.setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            // Android 10 and lower
            @Suppress("DEPRECATION")
            promptInfoBuilder.setDeviceCredentialAllowed(true)
        }

        val promptInfo = promptInfoBuilder.build()
        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Translates BiometricPrompt error codes into user-friendly explanations.
     */
    fun translateErrorCode(errorCode: Int, originalMessage: String): String {
        return when (errorCode) {
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED -> "Authentication was cancelled."

            BiometricPrompt.ERROR_LOCKOUT ->
                "Too many failed attempts. Biometric sensor is temporarily locked. Try again in 30 seconds or use device PIN."

            BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                "Too many failed attempts. Biometrics disabled until device PIN or password is verified."

            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                "No biometrics or device screen lock configured on this device."

            BiometricPrompt.ERROR_HW_NOT_PRESENT ->
                "Biometric hardware not available on this device."

            BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                "Biometric sensor is busy or unavailable. Please try again."

            BiometricPrompt.ERROR_TIMEOUT ->
                "Authentication timed out. Tap unlock to try again."

            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
                "A system security update is required to use biometrics."

            else -> originalMessage.ifBlank { "Authentication failed (Code $errorCode)" }
        }
    }

    /**
     * Creates an Intent to navigate the user to device security/biometric enrollment settings.
     */
    fun createEnrollmentIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
                )
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }
}
