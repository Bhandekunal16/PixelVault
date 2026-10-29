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
import kotlinx.coroutines.launch

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

    fun setMetadata(metadata: ImageMetadata) {
        _uiState.update { it.copy(metadata = metadata) }
        fetchImageBytes(metadata.effectiveId)
    }

    fun fetchImageBytes(id: String) {
        if (id.isBlank()) return
        viewModelScope.launch {
            imageRepository.getImageBytes(id).collect { resource ->
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
        val filename = metadata.filename ?: "download_${metadata.effectiveId}.${metadata.format?.lowercase() ?: "jpg"}"
        val mime = metadata.mimetype ?: "image/jpeg"

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
        val id = _uiState.value.metadata?.effectiveId ?: return
        _uiState.update { it.copy(isDeleting = true, errorMessage = null, infoMessage = null) }

        viewModelScope.launch {
            var deleteError: String? = null
            imageRepository.deleteImages(listOf(id)).collect { resource ->
                when (resource) {
                    is Resource.Loading -> Unit
                    is Resource.Success -> Unit
                    is Resource.Error -> deleteError = resource.message
                }
            }

            var imageStillExists = false
            var imageNotFound = false
            var verificationError: String? = null

            imageRepository.getImageBytes(id).collect { resource ->
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
}
