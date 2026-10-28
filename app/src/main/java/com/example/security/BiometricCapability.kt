package com.example.security

/**
 * Represents the security and biometric capability status of the host device.
 */
sealed class BiometricCapability {
    /** Strong biometric sensor (fingerprint, Class 3 face unlock) enrolled and ready */
    data object BiometricReady : BiometricCapability()

    /** Device credential (PIN, pattern, or password) is set up, but no biometrics enrolled */
    data object DeviceCredentialOnly : BiometricCapability()

    /** Hardware exists, but neither biometrics nor device lock credentials are set up */
    data object NoneEnrolled : BiometricCapability()

    /** Device does not have biometric hardware */
    data object NoHardware : BiometricCapability()

    /** Biometric sensor is temporarily unavailable (e.g. in use or error) */
    data object HardwareUnavailable : BiometricCapability()

    /** Security vulnerability requires an OS update before biometrics can be used */
    data object SecurityUpdateRequired : BiometricCapability()

    /** Unknown or unsupported state */
    data class Unknown(val statusCode: Int) : BiometricCapability()

    val canAuthenticate: Boolean
        get() = this is BiometricReady || this is DeviceCredentialOnly

    val displayName: String
        get() = when (this) {
            is BiometricReady -> "Biometrics & Device PIN Ready"
            is DeviceCredentialOnly -> "Device PIN / Screen Lock Configured"
            is NoneEnrolled -> "No Screen Lock or Biometrics Set"
            is NoHardware -> "No Biometric Hardware (PIN/Pattern Only)"
            is HardwareUnavailable -> "Sensor Temporarily Unavailable"
            is SecurityUpdateRequired -> "Security Update Required"
            is Unknown -> "Status Code: $statusCode"
        }
}
