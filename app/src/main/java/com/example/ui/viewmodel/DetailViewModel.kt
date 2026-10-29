package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ImageMetadata
import com.example.data.repository.ImageRepository
import com.example.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

data class DetailUiState(
    val metadata: ImageMetadata? = null,
    val imageBytes: ByteArray? = null,
    val isLoadingBytes: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadedUri: Uri? = null,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class DetailViewModel(
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()
    private var activeImageId = ""
    private var activeAccount = ""
    private var imageRequestJob: Job? = null

    fun setMetadata(metadata: ImageMetadata) {
        val id = metadata.effectiveId
        val account = normalizeAccount(metadata.email.orEmpty())
        val changedImage = id != activeImageId || account != activeAccount
        if (changedImage) {
            activeImageId = id
            activeAccount = account
            imageRequestJob?.cancel()
        }
        _uiState.update {
            it.copy(
                metadata = metadata,
                imageBytes = if (changedImage) null else it.imageBytes,
                isLoadingBytes = changedImage,
                errorMessage = if (changedImage) null else it.errorMessage
            )
        }
        if (changedImage || (_uiState.value.imageBytes == null && !id.isBlank())) {
            fetchImageBytes(id)
        }
    }

    fun fetchImageBytes(id: String, forceRefresh: Boolean = false) {
        if (id.isBlank()) return
        if (!forceRefresh && activeImageId == id && imageRequestJob?.isActive == true) return
        if (!forceRefresh && activeImageId == id && _uiState.value.imageBytes != null) return

        val changedImage = activeImageId != id
        if (changedImage) activeAccount = normalizeAccount(_uiState.value.metadata?.email.orEmpty())
        activeImageId = id
        val account = activeAccount
        imageRequestJob?.cancel()
        _uiState.update {
            it.copy(
                imageBytes = if (changedImage) null else it.imageBytes,
                isLoadingBytes = true,
                errorMessage = null
            )
        }
        imageRequestJob = viewModelScope.launch {
            imageRepository.getImageBytes(id, account).collect { resource ->
                if (activeImageId != id || activeAccount != account) return@collect
                when (resource) {
                    is Resource.Loading -> _uiState.update { it.copy(isLoadingBytes = true, errorMessage = null) }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isLoadingBytes = false,
                                imageBytes = resource.data,
                                errorMessage = null
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoadingBytes = false,
                                imageBytes = if (changedImage) null else it.imageBytes,
                                errorMessage = resource.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun downloadImage(context: Context) {
        val bytes = _uiState.value.imageBytes
        val metadata = _uiState.value.metadata
        if (bytes == null || metadata == null) {
            _uiState.update { it.copy(errorMessage = "Image bytes not loaded yet.") }
            return
        }

        _uiState.update { it.copy(isDownloading = true, errorMessage = null, infoMessage = null) }
        val filename = metadata.effectiveFilename
            ?: "download_${metadata.effectiveId}.${metadata.format?.lowercase() ?: "jpg"}"
        val mime = metadata.effectiveMimeType ?: "image/jpeg"

        val result = imageRepository.saveToDownloads(context, bytes, filename, mime)
        result.onSuccess { uri ->
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    downloadedUri = uri,
                    infoMessage = "Saved to Downloads: $filename"
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    errorMessage = "Failed to save image: ${err.message}"
                )
            }
        }
    }

    fun deleteImage(onDeleted: () -> Unit) {
        if (_uiState.value.isDeleting) return
        val metadata = _uiState.value.metadata ?: return
        val id = metadata.effectiveId
        val account = normalizeAccount(metadata.email.orEmpty())
        _uiState.update { it.copy(isDeleting = true, errorMessage = null, infoMessage = null) }

        viewModelScope.launch {
            var deleteError: String? = null
            imageRepository.deleteImages(listOf(id), account).collect { resource ->
                if (activeImageId != id || activeAccount != account) return@collect
                when (resource) {
                    is Resource.Loading -> Unit
                    is Resource.Success -> Unit
                    is Resource.Error -> deleteError = resource.message
                }
            }

            var imageStillExists = false
            var imageNotFound = false
            var verificationError: String? = null

            imageRepository.getImageBytes(id, account).collect { resource ->
                if (activeImageId != id || activeAccount != account) return@collect
                when (resource) {
                    is Resource.Loading -> Unit
                    is Resource.Success -> imageStillExists = true
                    is Resource.Error -> when {
                        resource.statusCode == 404 -> imageNotFound = true
                        resource.statusCode in 200..299 -> imageStillExists = true
                        else -> verificationError = resource.message
                    }
                }
            }

            if (activeImageId != id || activeAccount != account) return@launch
            when {
                imageNotFound -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            isDeleted = true,
                            infoMessage = "Image deleted successfully."
                        )
                    }
                    onDeleted()
                }
                verificationError != null -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = if (deleteError == null) {
                                "Could not verify whether the image was deleted: $verificationError"
                            } else {
                                "Delete failed: $deleteError Verification also failed: $verificationError"
                            }
                        )
                    }
                }
                imageStillExists -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = deleteError
                                ?: "The image is still available. The deletion could not be confirmed."
                        )
                    }
                }
                else -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = deleteError
                                ?: "The server did not confirm whether the image was deleted."
                        )
                    }
                }
            }
        }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    private fun normalizeAccount(email: String): String = email.trim().lowercase(Locale.ROOT)
}
