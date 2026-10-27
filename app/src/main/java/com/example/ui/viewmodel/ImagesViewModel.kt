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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImagesUiState(
    val images: List<ImageMetadata> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
    val deleteSuccessMessage: String? = null,
    val userEmail: String = ""
)

class ImagesViewModel(
    private val authRepository: AuthRepository,
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImagesUiState())
    val uiState: StateFlow<ImagesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collect { email ->
                if (email != null) {
                    _uiState.update { it.copy(userEmail = email) }
                    loadImages(email, isRefresh = false)
                }
            }
        }
    }

    fun loadImages(emailOverride: String? = null, isRefresh: Boolean = false) {
        val email = emailOverride ?: _uiState.value.userEmail
        if (email.isBlank()) return

        viewModelScope.launch {
            imageRepository.getImages(email).collect { resource ->
                when (resource) {
                    is Resource.Loading -> {
                        _uiState.update {
                            if (isRefresh) it.copy(isRefreshing = true, errorMessage = null)
                            else it.copy(isLoading = true, errorMessage = null)
                        }
                    }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                images = resource.data,
                                isLoading = false,
                                isRefreshing = false,
                                errorMessage = null,
                                // Remove ids that no longer exist
                                selectedIds = it.selectedIds.intersect(resource.data.map { img -> img.effectiveId }.toSet())
                            )
                        }
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                errorMessage = resource.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleMultiSelectMode() {
        _uiState.update {
            val newMode = !it.isMultiSelectMode
            it.copy(
                isMultiSelectMode = newMode,
                selectedIds = if (newMode) it.selectedIds else emptySet()
            )
        }
    }

    fun toggleSelection(id: String) {
        _uiState.update {
            val current = it.selectedIds.toMutableSet()
            if (current.contains(id)) {
                current.remove(id)
            } else {
                current.add(id)
            }
            it.copy(
                selectedIds = current,
                isMultiSelectMode = if (current.isEmpty()) it.isMultiSelectMode else true
            )
        }
    }

    fun selectAll() {
        _uiState.update {
            val allIds = it.images.map { img -> img.effectiveId }.filter { id -> id.isNotBlank() }.toSet()
            it.copy(
                selectedIds = allIds,
                isMultiSelectMode = true
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedIds = emptySet(),
                isMultiSelectMode = false
            )
        }
    }

    fun deleteSelected(onDeleted: () -> Unit = {}) {
        val idsToDelete = _uiState.value.selectedIds.toList()
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch {
            imageRepository.deleteImages(idsToDelete).collect { resource ->
                when (resource) {
                    is Resource.Loading -> _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
                    is Resource.Success -> {
                        val count = resource.data
                        _uiState.update {
                            it.copy(
                                isDeleting = false,
                                isMultiSelectMode = false,
                                selectedIds = emptySet(),
                                deleteSuccessMessage = "Deleted $count image${if (count != 1) "s" else ""} successfully."
                            )
                        }
                        loadImages(isRefresh = true)
                        onDeleted()
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isDeleting = false,
                                errorMessage = resource.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun deleteSingle(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            imageRepository.deleteImages(listOf(id)).collect { resource ->
                when (resource) {
                    is Resource.Loading -> _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isDeleting = false,
                                deleteSuccessMessage = "Image deleted successfully."
                            )
                        }
                        loadImages(isRefresh = true)
                        onDeleted()
                    }
                    is Resource.Error -> {
                        _uiState.update {
                            it.copy(
                                isDeleting = false,
                                errorMessage = resource.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearDeleteMessage() {
        _uiState.update { it.copy(deleteSuccessMessage = null) }
    }
}
