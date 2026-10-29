package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ImageMetadata
import com.example.data.repository.AuthRepository
import com.example.data.repository.ImageRepository
import com.example.util.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class DashboardUiState(
    val userEmail: String = "",
    val userId: String = "",
    val userRole: String = "user",
    val totalImagesCount: Int = 0,
    val recentImages: List<ImageMetadata> = emptyList(),
    val totalOriginalBytes: Long = 0L,
    val totalStoredBytes: Long = 0L,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
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
    private var activeAccount = ""
    private var imagesObservationJob: Job? = null
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collectLatest { email ->
                observeAccount(email.orEmpty().trim().lowercase(Locale.ROOT))
            }
        }
        viewModelScope.launch {
            authRepository.getCurrentUserId().collect { id ->
                _uiState.update { it.copy(userId = id.orEmpty()) }
            }
        }
        viewModelScope.launch {
            authRepository.getCurrentUserRole().collect { role ->
                _uiState.update { it.copy(userRole = role ?: "user") }
            }
        }
    }

    private fun observeAccount(account: String) {
        if (activeAccount == account) return
        activeAccount = account
        imagesObservationJob?.cancel()
        syncJob?.cancel()

        if (account.isBlank()) {
            _uiState.value = DashboardUiState()
            return
        }

        _uiState.value = DashboardUiState(userEmail = account, isLoading = true)
        imagesObservationJob = viewModelScope.launch {
            imageRepository.observeImages(account).collect { images ->
                if (activeAccount != account) return@collect
                updateImages(images)
            }
        }
    }

    private fun updateImages(images: List<ImageMetadata>) {
        val originalBytes = images.sumOf { it.originalSize ?: 0L }
        val storedBytes = images.sumOf { it.storedSize ?: (it.originalSize ?: 0L) }
        _uiState.update {
            it.copy(
                isLoading = false,
                totalImagesCount = images.size,
                recentImages = images.take(6),
                totalOriginalBytes = originalBytes,
                totalStoredBytes = storedBytes,
                errorMessage = null
            )
        }
    }

    fun loadDashboardData(emailOverride: String? = null) {
        val account = emailOverride?.trim()?.lowercase(Locale.ROOT)
            ?: _uiState.value.userEmail
        observeAccount(account)
    }

    fun syncDashboardIfStale() {
        synchronizeDashboard(forceRefresh = false)
    }

    fun refreshDashboardData() {
        synchronizeDashboard(forceRefresh = true)
    }

    private fun synchronizeDashboard(forceRefresh: Boolean) {
        val account = activeAccount
        if (account.isBlank() || _uiState.value.isSyncing) return
        syncJob = viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, errorMessage = null) }
            val result = imageRepository.synchronizeImages(account, forceRefresh)
            if (activeAccount != account) return@launch
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    errorMessage = (result as? Resource.Error)?.message
                )
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
