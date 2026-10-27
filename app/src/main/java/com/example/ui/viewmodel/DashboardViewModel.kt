package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ImageMetadata
import com.example.data.repository.AuthRepository
import com.example.data.repository.ImageRepository
import com.example.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val userEmail: String = "",
    val userId: String = "",
    val userRole: String = "user",
    val totalImagesCount: Int = 0,
    val recentImages: List<ImageMetadata> = emptyList(),
    val totalOriginalBytes: Long = 0L,
    val totalStoredBytes: Long = 0L,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val totalSavingsBytes: Long
        get() = if (totalOriginalBytes > totalStoredBytes) totalOriginalBytes - totalStoredBytes else 0L

    val savingsPercentage: Int
        get() = if (totalOriginalBytes > 0 && totalOriginalBytes > totalStoredBytes) {
            (((totalOriginalBytes - totalStoredBytes).toDouble() / totalOriginalBytes.toDouble()) * 100).toInt()
        } else 0
}

class DashboardViewModel(
    private val authRepository: AuthRepository,
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collect { email ->
                if (email != null) {
                    _uiState.update { it.copy(userEmail = email) }
                    loadDashboardData(email)
                }
            }
        }
        viewModelScope.launch {
            authRepository.getCurrentUserId().collect { id ->
                if (id != null) {
                    _uiState.update { it.copy(userId = id) }
                }
            }
        }
        viewModelScope.launch {
            authRepository.getCurrentUserRole().collect { role ->
                if (role != null) {
                    _uiState.update { it.copy(userRole = role) }
                }
            }
        }
    }

    fun loadDashboardData(emailOverride: String? = null) {
        val email = emailOverride ?: _uiState.value.userEmail
        if (email.isBlank()) return

        viewModelScope.launch {
            imageRepository.getImages(email).collect { resource ->
                when (resource) {
                    is Resource.Loading -> _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                    is Resource.Success -> {
                        val images = resource.data
                        val origSum = images.sumOf { it.originalSize ?: 0L }
                        val storedSum = images.sumOf { it.storedSize ?: (it.originalSize ?: 0L) }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                totalImagesCount = images.size,
                                recentImages = images.take(6),
                                totalOriginalBytes = origSum,
                                totalStoredBytes = storedSum,
                                errorMessage = null
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = resource.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLoggedOut()
        }
    }
}
