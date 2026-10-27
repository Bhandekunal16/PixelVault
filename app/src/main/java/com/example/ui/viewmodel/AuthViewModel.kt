package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.User
import com.example.data.repository.AuthRepository
import com.example.util.Resource
import com.example.util.ValidationUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isPasswordVisible: Boolean = false,
    val isConfirmPasswordVisible: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val isLoading: Boolean = false,
    val generalError: String? = null,
    val successMessage: String? = null,
    val loggedInUser: User? = null
)

sealed class AuthEvent {
    data object NavigateToDashboard : AuthEvent()
    data object NavigateToLogin : AuthEvent()
    data class ShowToast(val message: String) : AuthEvent()
}

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>()
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collect { email ->
                if (email != null && _uiState.value.email.isBlank()) {
                    _uiState.update { it.copy(email = email) }
                }
            }
        }
    }

    fun onEmailChanged(value: String) {
        val trimmed = value.trim()
        val error = if (trimmed.isNotEmpty() && !ValidationUtil.isValidGmail(trimmed)) {
            "Only Gmail addresses are accepted (e.g. name@gmail.com)"
        } else null

        _uiState.update { it.copy(email = value, emailError = error, generalError = null) }
    }

    fun onPasswordChanged(value: String) {
        val error = if (value.isNotEmpty() && !ValidationUtil.isValidPassword(value)) {
            "Password must be at least 8 characters long"
        } else null

        val confirmError = if (_uiState.value.confirmPassword.isNotEmpty() &&
            !ValidationUtil.doPasswordsMatch(value, _uiState.value.confirmPassword)) {
            "Passwords do not match"
        } else null

        _uiState.update {
            it.copy(
                password = value,
                passwordError = error,
                confirmPasswordError = confirmError,
                generalError = null
            )
        }
    }

    fun onConfirmPasswordChanged(value: String) {
        val error = if (value.isNotEmpty() && !ValidationUtil.doPasswordsMatch(_uiState.value.password, value)) {
            "Passwords do not match"
        } else null

        _uiState.update {
            it.copy(
                confirmPassword = value,
                confirmPasswordError = error,
                generalError = null
            )
        }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun toggleConfirmPasswordVisibility() {
        _uiState.update { it.copy(isConfirmPasswordVisible = !it.isConfirmPasswordVisible) }
    }

    fun login() {
        val state = _uiState.value
        if (state.isLoading) return // Prevent duplicate submission

        val emailTrimmed = state.email.trim()
        val isEmailValid = ValidationUtil.isValidGmail(emailTrimmed)
        val isPasswordValid = ValidationUtil.isValidPassword(state.password)

        if (!isEmailValid || !isPasswordValid) {
            _uiState.update {
                it.copy(
                    emailError = if (!isEmailValid) "Valid Gmail address required (@gmail.com)" else null,
                    passwordError = if (!isPasswordValid) "Password must be at least 8 characters" else null
                )
            }
            return
        }

        viewModelScope.launch {
            authRepository.login(emailTrimmed, state.password).collect { resource ->
                when (resource) {
                    is Resource.Loading -> _uiState.update { it.copy(isLoading = true, generalError = null) }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                loggedInUser = resource.data,
                                generalError = null
                            )
                        }
                        _events.emit(AuthEvent.NavigateToDashboard)
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                generalError = resource.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun register() {
        val state = _uiState.value
        if (state.isLoading) return // Prevent duplicate submission

        val emailTrimmed = state.email.trim()
        val isEmailValid = ValidationUtil.isValidGmail(emailTrimmed)
        val isPasswordValid = ValidationUtil.isValidPassword(state.password)
        val doPasswordsMatch = ValidationUtil.doPasswordsMatch(state.password, state.confirmPassword)

        if (!isEmailValid || !isPasswordValid || !doPasswordsMatch) {
            _uiState.update {
                it.copy(
                    emailError = if (!isEmailValid) "Valid Gmail address required (@gmail.com)" else null,
                    passwordError = if (!isPasswordValid) "Password must be at least 8 characters" else null,
                    confirmPasswordError = if (!doPasswordsMatch) "Passwords do not match" else null
                )
            }
            return
        }

        viewModelScope.launch {
            authRepository.register(emailTrimmed, state.password).collect { resource ->
                when (resource) {
                    is Resource.Loading -> _uiState.update { it.copy(isLoading = true, generalError = null) }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                successMessage = resource.data,
                                generalError = null
                            )
                        }
                        _events.emit(AuthEvent.ShowToast(resource.data))
                        _events.emit(AuthEvent.NavigateToLogin)
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                generalError = resource.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(generalError = null, successMessage = null) }
    }
}
