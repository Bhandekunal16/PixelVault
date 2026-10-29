package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.StorageUsageEntity
import com.example.data.local.QueuedUploadEntity
import com.example.data.local.UploadQueueStatus
import com.example.data.model.ImageMetadata
import com.example.data.model.OutputFormat
import com.example.data.repository.AuthRepository
import com.example.data.repository.ImageRepository
import com.example.util.FileUtils
import com.example.util.Resource
import com.example.util.ValidationUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

data class UploadUiState(
    val selectedUri: Uri? = null,
    val filename: String = "",
    val fileSizeBytes: Long = 0L,
    val email: String = "",
    val selectedFormat: OutputFormat = OutputFormat.WEBP,
    val isUploading: Boolean = false,
    val isQueueing: Boolean = false,
    val storageUsage: StorageUsageEntity? = null,
    val isUsageRefreshing: Boolean = false,
    val storageUsageErrorMessage: String? = null,
    val queuedUploads: List<QueuedUploadEntity> = emptyList(),
    val pendingUploadCount: Int = 0,
    val queueActionIds: Set<String> = emptySet(),
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
    private val _uploadCompletedEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val uploadCompletedEvents: SharedFlow<String> = _uploadCompletedEvents.asSharedFlow()
    private val _queueNoticeEvents = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val queueNoticeEvents: SharedFlow<String?> = _queueNoticeEvents.asSharedFlow()
    private var activeAccount = ""
    private var usageObservationJob: kotlinx.coroutines.Job? = null
    private var usageRefreshJob: kotlinx.coroutines.Job? = null
    private var queueObservationJob: kotlinx.coroutines.Job? = null
    private val awaitingCompletionIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            authRepository.getCurrentUserEmail().collectLatest { email ->
                val account = email.orEmpty().trim().lowercase(Locale.ROOT)
                activeAccount = account
                usageObservationJob?.cancel()
                usageRefreshJob?.cancel()
                queueObservationJob?.cancel()
                awaitingCompletionIds.clear()
                _uiState.update {
                    it.copy(
                        email = account,
                        selectedUri = null,
                        filename = "",
                        fileSizeBytes = 0L,
                        storageUsage = null,
                        isUsageRefreshing = false,
                        storageUsageErrorMessage = null,
                        queuedUploads = emptyList(),
                        pendingUploadCount = 0,
                        queueActionIds = emptySet(),
                        isQueueing = false,
                        isUploading = false,
                        uploadedMetadata = null,
                        errorMessage = null,
                        errorCode = null,
                        emailError = null
                    )
                }
                if (account.isNotBlank()) {
                    usageObservationJob = viewModelScope.launch {
                        imageRepository.observeStorageUsage(account).collect { usage ->
                            if (activeAccount == account) {
                                _uiState.update { it.copy(storageUsage = usage) }
                            }
                        }
                    }
                    queueObservationJob = viewModelScope.launch {
                        imageRepository.observeQueuedUploads(account).collect { uploads ->
                            if (activeAccount != account) return@collect
                            _uiState.update {
                                it.copy(
                                    queuedUploads = uploads,
                                    pendingUploadCount = uploads.count {
                                        it.status != UploadQueueStatus.COMPLETED &&
                                            it.status != UploadQueueStatus.CANCELLED
                                    }
                                )
                            }
                            val completedIds = uploads
                                .filter {
                                    it.status == UploadQueueStatus.COMPLETED &&
                                        awaitingCompletionIds.remove(it.operationId)
                                }
                                .map(QueuedUploadEntity::operationId)
                            completedIds.forEach(_uploadCompletedEvents::tryEmit)
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
        if (uri == null || _uiState.value.isQueueing) return
        val name: String
        val size: Long
        try {
            name = FileUtils.getFileName(context, uri)
            size = FileUtils.getFileSize(context, uri)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = e.message ?: "Could not read the selected image.")
            }
            return
        }
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

    fun queueSelectedImage(context: Context) {
        val state = _uiState.value
        val uri = state.selectedUri ?: run {
            _uiState.update { it.copy(errorMessage = "Please select an image first.") }
            return
        }
        val account = activeAccount
        if (state.isQueueing || state.isUploading) return
        if (!ValidationUtil.isValidGmail(account)) {
            _uiState.update { it.copy(errorMessage = "Sign in before queuing an upload.") }
            return
        }
        if (state.fileSizeBytes > FileUtils.MAX_IMAGE_BYTES) {
            _uiState.update {
                it.copy(errorMessage = "Selected image exceeds the 10 MiB upload limit.")
            }
            return
        }

        _uiState.update {
            it.copy(
                isQueueing = true,
                errorMessage = null,
                errorCode = null
            )
        }
        _queueNoticeEvents.tryEmit(null)
        viewModelScope.launch {
            val result = try {
                imageRepository.queueUpload(
                    context = context,
                    uri = uri,
                    email = account,
                    format = state.selectedFormat
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeAccount == account) {
                    _uiState.update {
                        it.copy(
                            isQueueing = false,
                            errorMessage = e.message ?: "Could not save this image to the upload queue."
                        )
                    }
                }
                return@launch
            }
            if (activeAccount != account) return@launch
            when (result) {
                is Resource.Success -> {
                    awaitingCompletionIds.add(result.data.operationId)
                    _uiState.update {
                        it.copy(
                            isQueueing = false,
                            selectedUri = null,
                            filename = "",
                            fileSizeBytes = 0L,
                            errorMessage = null,
                            errorCode = null
                        )
                    }
                    _queueNoticeEvents.tryEmit(
                        "Image saved to the upload queue. It will upload when a connection is available."
                    )
                    if (
                        result.data.status == UploadQueueStatus.COMPLETED ||
                        _uiState.value.queuedUploads.any {
                            it.operationId == result.data.operationId &&
                                it.status == UploadQueueStatus.COMPLETED
                        }
                    ) {
                        awaitingCompletionIds.remove(result.data.operationId)
                        _uploadCompletedEvents.tryEmit(result.data.operationId)
                    }
                }
                is Resource.Error -> _uiState.update {
                    it.copy(
                        isQueueing = false,
                        errorMessage = result.message,
                        errorCode = result.errorCode
                    )
                }
                Resource.Loading -> _uiState.update { it.copy(isQueueing = false) }
            }
        }
    }

    fun retryQueuedUpload(operationId: String) =
        performQueueAction(operationId, "Upload retry scheduled.") { account ->
            imageRepository.retryQueuedUpload(operationId, account)
        }

    fun cancelQueuedUpload(operationId: String) =
        performQueueAction(operationId, "Queued upload cancelled.") { account ->
            imageRepository.cancelQueuedUpload(operationId, account)
        }

    fun removeQueuedUpload(operationId: String) =
        performQueueAction(operationId, "Upload removed from the queue.") { account ->
            imageRepository.removeQueuedUpload(operationId, account)
        }

    private fun performQueueAction(
        operationId: String,
        successMessage: String,
        action: suspend (String) -> Resource<Unit>
    ) {
        val account = activeAccount
        if (
            account.isBlank() ||
            operationId.isBlank() ||
            operationId in _uiState.value.queueActionIds
        ) return
        _queueNoticeEvents.tryEmit(null)
        _uiState.update {
            it.copy(
                queueActionIds = it.queueActionIds + operationId,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            val result = try {
                action(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Resource.Error(e.message ?: "Could not update the upload queue.", e)
            }
            if (activeAccount != account) return@launch
            _uiState.update { state ->
                state.copy(
                    queueActionIds = state.queueActionIds - operationId,
                    errorMessage = (result as? Resource.Error)?.message
                )
            }
            if (result is Resource.Success) _queueNoticeEvents.tryEmit(successMessage)
        }
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
