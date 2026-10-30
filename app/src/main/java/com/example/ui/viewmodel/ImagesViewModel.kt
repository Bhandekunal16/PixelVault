package com.example.ui.viewmodel

import android.content.Context
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

data class ImagesUiState(
    val images: List<ImageMetadata> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val syncErrorMessage: String? = null,
    val lastSuccessfulSyncAt: Long? = null,
    val isMultiSelectMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
    val deleteSuccessMessage: String? = null,
    val actionInfoMessage: String? = null,
    val isPerformingListAction: Boolean = false,
    val userEmail: String = ""
)

class ImagesViewModel(
    private val authRepository: AuthRepository,
    private val imageRepository: ImageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImagesUiState())
    val uiState: StateFlow<ImagesUiState> = _uiState.asStateFlow()
    private var activeAccount = ""
    private var imagesObservationJob: Job? = null
    private var syncStateJob: Job? = null
    private var syncJob: Job? = null
    private var deleteJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collectLatest { email ->
                val account = normalizeAccount(email.orEmpty())
                if (account == activeAccount) return@collectLatest

                activeAccount = account
                imagesObservationJob?.cancel()
                syncStateJob?.cancel()
                syncJob?.cancel()
                deleteJob?.cancel()

                if (account.isBlank()) {
                    _uiState.value = ImagesUiState()
                    return@collectLatest
                }

                _uiState.value = ImagesUiState(
                    isLoading = true,
                    isSyncing = true,
                    isRefreshing = true,
                    userEmail = account
                )
                observeAccount(account)
                synchronize(account, forceRefresh = false)
            }
        }
    }

    private fun observeAccount(account: String) {
        imagesObservationJob = viewModelScope.launch {
            imageRepository.observeImages(account).collect { images ->
                if (activeAccount != account) return@collect
                _uiState.update {
                    it.copy(
                        images = images,
                        isLoading = false,
                        selectedIds = it.selectedIds.intersect(images.map(ImageMetadata::effectiveId).toSet())
                    )
                }
            }
        }
        syncStateJob = viewModelScope.launch {
            imageRepository.observeSyncState(account).collect { syncState ->
                if (activeAccount != account) return@collect
                _uiState.update {
                    it.copy(
                        lastSuccessfulSyncAt = syncState?.lastSuccessfulSyncAt,
                        syncErrorMessage = syncState?.lastSyncError
                    )
                }
            }
        }
    }

    fun syncIfStale() {
        val account = activeAccount
        if (account.isBlank() || _uiState.value.isSyncing) return
        synchronize(account, forceRefresh = false)
    }

    fun refreshImages() {
        val account = activeAccount
        if (account.isBlank() || _uiState.value.isSyncing) return
        synchronize(account, forceRefresh = true)
    }

    private fun synchronize(account: String, forceRefresh: Boolean) {
        syncJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSyncing = true,
                    isRefreshing = it.images.isNotEmpty(),
                    syncErrorMessage = null
                )
            }
            val result = imageRepository.synchronizeImages(account, forceRefresh)
            if (activeAccount != account) return@launch
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    isRefreshing = false,
                    syncErrorMessage = (result as? Resource.Error)?.message
                )
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
            if (current.contains(id)) current.remove(id) else current.add(id)
            it.copy(
                selectedIds = current,
                isMultiSelectMode = if (current.isEmpty()) it.isMultiSelectMode else true
            )
        }
    }

    fun selectAll() {
        _uiState.update {
            val allIds = it.images.map(ImageMetadata::effectiveId)
                .filter(String::isNotBlank)
                .toSet()
            it.copy(selectedIds = allIds, isMultiSelectMode = true)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), isMultiSelectMode = false) }
    }

    fun deleteSelected(onDeleted: () -> Unit = {}) {
        val state = _uiState.value
        val idsToDelete = state.selectedIds.toList()
        if (idsToDelete.isEmpty() || state.isDeleting) return
        deleteImages(idsToDelete, onDeleted)
    }

    fun deleteSingle(id: String, onDeleted: () -> Unit = {}) {
        if (_uiState.value.isDeleting) return
        deleteImages(listOf(id), onDeleted)
    }

    fun confirmDeleted(id: String) {
        val account = activeAccount
        if (account.isBlank() || id.isBlank()) return
        viewModelScope.launch {
            if (activeAccount != account) return@launch
            imageRepository.removeCachedImages(account, listOf(id))
        }
    }

    private fun deleteImages(ids: List<String>, onDeleted: () -> Unit) {
        val account = activeAccount
        if (account.isBlank()) return
        _uiState.update { it.copy(isDeleting = true, errorMessage = null, deleteSuccessMessage = null) }
        deleteJob = viewModelScope.launch {
            imageRepository.deleteImages(ids, account).collect { resource ->
                if (activeAccount != account) return@collect
                when (resource) {
                    is Resource.Loading -> Unit
                    is Resource.Success -> {
                        _uiState.update {
                            it.copy(
                                isDeleting = false,
                                isMultiSelectMode = false,
                                selectedIds = emptySet(),
                                deleteSuccessMessage =
                                    "Deleted ${resource.data} image${if (resource.data != 1) "s" else ""} successfully."
                            )
                        }
                        onDeleted()
                    }
                    is Resource.Error -> _uiState.update {
                        it.copy(isDeleting = false, errorMessage = resource.message)
                    }
                }
            }
        }
    }

    fun clearDeleteMessage() {
        _uiState.update { it.copy(deleteSuccessMessage = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionInfoMessage = null) }
    }

    fun downloadSelectedImage(context: Context) {
        val state = _uiState.value
        if (state.isPerformingListAction || state.selectedIds.size != 1) return
        val id = state.selectedIds.first()
        val metadata = state.images.find { it.effectiveId == id } ?: return
        val account = activeAccount
        if (account.isBlank()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isPerformingListAction = true, errorMessage = null, actionInfoMessage = null)
            }
            var bytes: ByteArray? = null
            var fetchError: String? = null
            imageRepository.getImageBytes(id, account).collect { resource ->
                if (activeAccount != account) return@collect
                when (resource) {
                    is Resource.Loading -> Unit
                    is Resource.Success -> bytes = resource.data
                    is Resource.Error -> fetchError = resource.message
                }
            }
            if (activeAccount != account) return@launch

            val imageBytes = bytes
            if (imageBytes == null) {
                _uiState.update {
                    it.copy(
                        isPerformingListAction = false,
                        errorMessage = fetchError ?: "Could not download image bytes."
                    )
                }
                return@launch
            }

            val filename = metadata.effectiveFilename
                ?: "download_${metadata.effectiveId}.${metadata.format?.lowercase() ?: "jpg"}"
            val mime = metadata.effectiveMimeType ?: "image/jpeg"
            val result = imageRepository.saveToDownloads(context, imageBytes, filename, mime)
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isPerformingListAction = false,
                        actionInfoMessage = "Saved to Downloads: $filename"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isPerformingListAction = false,
                        errorMessage = "Failed to save image: ${err.message}"
                    )
                }
            }
        }
    }

    fun clearSyncError() {
        _uiState.update { it.copy(syncErrorMessage = null) }
    }

    private fun normalizeAccount(email: String): String = email.trim().lowercase(Locale.ROOT)
}
