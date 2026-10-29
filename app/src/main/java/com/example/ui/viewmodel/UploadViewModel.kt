package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.StorageUsageEntity
import com.example.data.model.ImageMetadata
import com.example.data.model.OutputFormat
import com.example.data.repository.AuthRepository
import com.example.data.repository.ImageRepository
import com.example.util.FileUtils
import com.example.util.Resource
import com.example.util.ValidationUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class UploadUiState(
    val selectedUri: Uri? = null,
    val filename: String = "",
    val fileSizeBytes: Long = 0L,
    val email: String = "",
    val selectedFormat: OutputFormat = OutputFormat.WEBP,
    val isUploading: Boolean = false,
    val storageUsage: StorageUsageEntity? = null,
    val isUsageRefreshing: Boolean = false,
    val storageUsageErrorMessage: String? = null,
    val uploadedMetadata: ImageMetadata? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val emailError: String? = null
)

class UploadViewModel(
    private val authRepository: AuthRepository,
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()
    private var activeAccount = ""
    private var usageObservationJob: kotlinx.coroutines.Job? = null
    private var usageRefreshJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collectLatest { email ->
                val account = email.orEmpty().trim().lowercase(Locale.ROOT)
                activeAccount = account
                usageObservationJob?.cancel()
                usageRefreshJob?.cancel()
                _uiState.update {
                    it.copy(
                        email = account,
                        storageUsage = null,
                        isUsageRefreshing = false,
                        storageUsageErrorMessage = null
                    )
                }
                if (account.isNotBlank()) {
                    usageObservationJob = viewModelScope.launch {
                        imageRepository.observeStorageUsage(account).collect { usage ->
                            if (activeAccount == account)
                                _uiState.update { it.copy(storageUsage = usage) }
                        }
                    }
                }
            }
        }
    }

    fun refreshStorageUsageIfStale() {
        val account = activeAccount
        if (account.isBlank() || _uiState.value.isUsageRefreshing) return
        usageRefreshJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isUsageRefreshing = true, storageUsageErrorMessage = null)
            }
            val result = imageRepository.refreshStorageUsage(account, forceRefresh = false)
            if (activeAccount != account) return@launch
            _uiState.update {
                it.copy(
                    isUsageRefreshing = false,
                    storageUsageErrorMessage = (result as? Resource.Error)?.message
                )
            }
        }
    }

    fun onImageSelected(uri: Uri?, context: Context) {
        if (uri == null) return
        val name = FileUtils.getFileName(context, uri)
        val size = FileUtils.getFileSize(context, uri)
        val error = if (size > FileUtils.MAX_IMAGE_BYTES) {
            "Selected file exceeds 10 MiB (${FileUtils.formatBytes(size)}). Please choose a smaller image."
        } else null

        _uiState.update {
            it.copy(
                selectedUri = uri,
                filename = name,
                fileSizeBytes = size,
                uploadedMetadata = null,
                errorMessage = error,
                errorCode = null
            )
        }
    }

    fun onFormatSelected(format: OutputFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    fun onEmailChanged(value: String) {
        // Registered account email is read-only throughout the entire application
    }

    fun upload(context: Context, onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        val uri = state.selectedUri ?: run {
            _uiState.update { it.copy(errorMessage = "Please select an image first.") }
            return
        }

        if (state.isUploading) return

        if (!ValidationUtil.isValidGmail(state.email)) {
            _uiState.update { it.copy(emailError = "Valid Gmail address required (@gmail.com)") }
            return
        }

        if (state.fileSizeBytes > FileUtils.MAX_IMAGE_BYTES) {
            _uiState.update { it.copy(errorMessage = "File size exceeds 10 MiB limit.") }
            return
        }

        _uiState.update {
            it.copy(isUploading = true, errorMessage = null, errorCode = null)
        }

        viewModelScope.launch {
            imageRepository.uploadImage(
                context = context,
                uri = uri,
                email = state.email.trim(),
                format = state.selectedFormat
            ).collect { resource ->
                when (resource) {
                    is Resource.Loading -> _uiState.update { it.copy(isUploading = true, errorMessage = null) }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                uploadedMetadata = resource.data,
                                errorMessage = null,
                                errorCode = null
                            )
                        }
                        onSuccess()
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isUploading = false,
                                errorMessage = resource.message,
                                errorCode = resource.errorCode
                            )
                        }
                    }
                }
            }
        }
    }

    fun reset() {
        _uiState.update {
            it.copy(
                selectedUri = null,
                filename = "",
                fileSizeBytes = 0L,
                uploadedMetadata = null,
                errorMessage = null,
                errorCode = null
            )
        }
    }
}
