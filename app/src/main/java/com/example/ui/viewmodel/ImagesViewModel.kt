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
            imageRepository.deleteImages(ids).collect { resource ->
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

    fun clearSyncError() {
        _uiState.update { it.copy(syncErrorMessage = null) }
    }

    private fun normalizeAccount(email: String): String = email.trim().lowercase(Locale.ROOT)
}
