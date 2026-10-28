package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.preferences.AppPreferences
import com.example.data.repository.AuthRepository
import com.example.data.repository.ImageRepository
import com.example.security.AppLockManager
import com.example.security.BiometricAuthManager
import com.example.security.BiometricCapability
import com.example.ui.theme.ThemeMode
import com.example.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val userEmail: String? = null,
    val userId: String? = null,
    val userRole: String = "user",
    val baseUrl: String = AppPreferences.DEFAULT_BASE_URL,
    val isTestingConnection: Boolean = false,
    val connectionTestResult: String? = null,
    val isConnectionSuccess: Boolean? = null,
    val encryptionEnabled: Boolean = false,
    val encryptionSecret: String = "",
    val encryptionAlgo: String = "AES/CBC/PKCS5Padding",
    val appLockEnabled: Boolean = false,
    val lockTimeoutSeconds: Long = 0L,
    val biometricCapability: BiometricCapability = BiometricCapability.BiometricReady,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appVersion: String = "1.0.0 (API v1)"
)

class SettingsViewModel(
    private val preferences: AppPreferences,
    private val authRepository: AuthRepository,
    private val imageRepository: ImageRepository,
    private val biometricAuthManager: BiometricAuthManager? = null,
    private val appLockManager: AppLockManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collect { email ->
                _uiState.update { it.copy(userEmail = email) }
            }
        }
        viewModelScope.launch {
            authRepository.getCurrentUserId().collect { id ->
                _uiState.update { it.copy(userId = id) }
            }
        }
        viewModelScope.launch {
            authRepository.getCurrentUserRole().collect { role ->
                _uiState.update { it.copy(userRole = role ?: "user") }
            }
        }
        viewModelScope.launch {
            preferences.baseUrlFlow.collect { url ->
                _uiState.update { it.copy(baseUrl = url) }
            }
        }
        viewModelScope.launch {
            preferences.encryptionEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(encryptionEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.encryptionSecretFlow.collect { secret ->
                _uiState.update { it.copy(encryptionSecret = secret ?: "") }
            }
        }
        viewModelScope.launch {
            preferences.appLockEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(appLockEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.lockTimeoutSecondsFlow.collect { seconds ->
                _uiState.update { it.copy(lockTimeoutSeconds = seconds) }
            }
        }
        viewModelScope.launch {
            preferences.themeModeFlow.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        refreshBiometricCapability()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    fun refreshBiometricCapability() {
        biometricAuthManager?.let { mgr ->
            _uiState.update { it.copy(biometricCapability = mgr.checkCapability()) }
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAppLockEnabled(enabled)
        }
    }

    fun setLockTimeoutSeconds(seconds: Long) {
        viewModelScope.launch {
            preferences.setLockTimeoutSeconds(seconds)
        }
    }

    fun lockNow() {
        appLockManager?.lockNow()
    }

    fun updateBaseUrl(newUrl: String) {
        viewModelScope.launch {
            preferences.setBaseUrl(newUrl.trim())
            _uiState.update { it.copy(connectionTestResult = null, isConnectionSuccess = null) }
        }
    }

    fun setPresetUrl(url: String) {
        updateBaseUrl(url)
    }

    fun testConnection() {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            imageRepository.checkServerHealth().collect { resource ->
                when (resource) {
                    is Resource.Loading -> _uiState.update {
                        it.copy(
                            isTestingConnection = true,
                            connectionTestResult = "Connecting to backend...",
                            isConnectionSuccess = null
                        )
                    }
                    is Resource.Success -> {
                        val latency = System.currentTimeMillis() - startTime
                        _uiState.update {
                            it.copy(
                                isTestingConnection = false,
                                connectionTestResult = "Connected successfully (${latency}ms latency). Server is alive at GET /",
                                isConnectionSuccess = true
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isTestingConnection = false,
                                connectionTestResult = "Connection failed: ${resource.message}",
                                isConnectionSuccess = false
                            )
                        }
                    }
                }
            }
        }
    }

    fun setEncryptionConfig(enabled: Boolean, secret: String) {
        viewModelScope.launch {
            preferences.setEncryptionConfig(
                enabled = enabled,
                secret = if (secret.isBlank()) null else secret.trim(),
                algo = _uiState.value.encryptionAlgo
            )
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            appLockManager?.reset()
            authRepository.logout()
            onLoggedOut()
        }
    }
}
