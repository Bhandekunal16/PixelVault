package com.example.ui.screens.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.OutputFormat
import com.example.data.local.QueuedUploadEntity
import com.example.data.local.UploadQueueStatus
import com.example.ui.components.StatusBanner
import com.example.ui.components.StorageUsageCard
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenContainerDark
import com.example.ui.theme.SuccessGreenContainerLight
import com.example.util.EmailUtils
import com.example.util.rememberHapticHelper
import com.example.ui.viewmodel.UploadViewModel
import com.example.util.FileUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest

@Composable
fun UploadScreen(
    viewModel: UploadViewModel,
    onUploadSuccess: () -> Unit,
    onNavigateToImages: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = rememberHapticHelper()
    val scrollState = rememberScrollState()

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    LaunchedEffect(uiState.email) {
        viewModel.refreshStorageUsageIfStale()
    }

    LaunchedEffect(viewModel) {
        viewModel.uploadCompletedEvents.collect {
            haptic.success()
            onUploadSuccess()
        }
    }

    TransientQueueNotice(viewModel.queueNoticeEvents)

    // Android zero-permission Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        viewModel.onImageSelected(uri, context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("upload_screen")
    ) {
        Text(
            text = "Upload & Optimize Image",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Convert formats and apply lossless or lossy optimization up to 10 MiB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.errorMessage != null) {
            StatusBanner(
                message = uiState.errorMessage!!,
                isError = true
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        StorageUsageCard(
            usage = uiState.storageUsage,
            isRefreshing = uiState.isUsageRefreshing,
            errorMessage = uiState.storageUsageErrorMessage,
            onRefresh = viewModel::refreshStorageUsageIfStale
        )

        if (uiState.queuedUploads.isNotEmpty()) {
            Text(
                text = "Upload queue (${uiState.pendingUploadCount} pending)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            uiState.queuedUploads.forEach { upload ->
                QueuedUploadCard(
                    upload = upload,
                    isActionRunning = upload.operationId in uiState.queueActionIds,
                    onRetry = { viewModel.retryQueuedUpload(upload.operationId) },
                    onCancel = { viewModel.cancelQueuedUpload(upload.operationId) },
                    onRemove = { viewModel.removeQueuedUpload(upload.operationId) },
                    onManageStorage = onNavigateToImages
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.errorCode == "STORAGE_QUOTA_EXCEEDED") {
            OutlinedButton(
                onClick = onNavigateToImages,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("manage_storage_images_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Manage images")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Solid Success Card if uploaded
        if (uiState.uploadedMetadata != null) {
            val meta = uiState.uploadedMetadata!!
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("upload_success_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) SuccessGreenContainerDark else SuccessGreenContainerLight
                ),
                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Upload & Processing Succeeded!",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = SuccessGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    UploadResultRow("Image ID", meta.effectiveId)
                    UploadResultRow("Stored Filename", meta.effectiveFilename ?: "N/A")
                    UploadResultRow("MIME Type", meta.effectiveMimeType ?: "N/A")
                    if (meta.width != null && meta.height != null) {
                        UploadResultRow("Dimensions", "${meta.width} × ${meta.height} px")
                    }
                    UploadResultRow("Original Size", FileUtils.formatBytes(meta.originalSize))
                    UploadResultRow("Stored Size", FileUtils.formatBytes(meta.storedSize))
                    UploadResultRow(
                        "Optimization",
                        if (meta.effectiveOptimized) "Optimized (${meta.savingsPercentage}% savings)" else "Standard conversion"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.reset()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.testTag("upload_another_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Upload Another")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Image Picker Area with Solid Surface and Crisp Border
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !uiState.isQueueing) {
                    haptic.click()
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                .testTag("image_picker_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            if (uiState.selectedUri != null) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(uiState.selectedUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Selected image preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().testTag("upload_image_preview")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.filename,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                text = FileUtils.formatBytes(uiState.fileSizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                haptic.click()
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !uiState.isQueueing,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("change_image_button")
                        ) {
                            Text("Change", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Tap to Select Image",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Opens Android Photo Picker (PNG, JPEG, WebP, etc. max 10 MiB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Target Output Format Selector
        Text(
            text = "Convert to Format",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Select destination image format:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutputFormat.entries.forEach { format ->
                val isSelected = uiState.selectedFormat == format
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (uiState.selectedFormat != format) {
                            haptic.tick()
                        }
                        viewModel.onFormatSelected(format)
                    },
                    label = { Text(format.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("format_chip_${format.apiValue}")
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = uiState.selectedFormat.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Read-Only Associated Gmail Account
        Text(
            text = "Associated Gmail Account (Read-Only)",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(6.dp))

        val displayEmail = EmailUtils.formatDisplayGmail(uiState.email, fallback = "Authenticated Account")

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("upload_email_input"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = "Authenticated Gmail",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Authenticated Gmail",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = displayEmail,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Read-Only",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Locked",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Text(
            text = "Images are automatically linked to your authenticated Gmail account",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // The queue action persists the selected bytes locally before scheduling network work.
        Button(
            onClick = {
                haptic.click()
                viewModel.queueSelectedImage(context)
            },
            enabled = uiState.selectedUri != null &&
                !uiState.isUploading &&
                !uiState.isQueueing &&
                uiState.email.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("upload_submit_button"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
        ) {
            if (uiState.isQueueing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Saving image on this device...", color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add to Upload Queue", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onPrimary)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun TransientQueueNotice(events: SharedFlow<String?>) {
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(events) {
        events.collectLatest { nextMessage ->
            if (nextMessage == null) {
                message = null
            } else {
                message = nextMessage
                delay(4_000)
                message = null
            }
        }
    }

    if (message != null) {
        StatusBanner(message = message!!, isSuccess = true)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun QueuedUploadCard(
    upload: QueuedUploadEntity,
    isActionRunning: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onManageStorage: () -> Unit
) {
    val isFailed = upload.status == UploadQueueStatus.PERMANENT_FAILURE ||
        upload.status == UploadQueueStatus.RETRYABLE_FAILURE
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (upload.status == UploadQueueStatus.UPLOADING) {
                        Icons.Default.CloudUpload
                    } else {
                        Icons.Default.Schedule
                    },
                    contentDescription = null,
                    tint = if (isFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = upload.originalFilename,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "${FileUtils.formatBytes(upload.fileSizeBytes)} · ${upload.status.toUploadStatusLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (upload.status == UploadQueueStatus.UPLOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            if (!upload.lastErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = upload.lastErrorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            val canRetry = upload.status == UploadQueueStatus.RETRYABLE_FAILURE ||
                upload.status == UploadQueueStatus.PERMANENT_FAILURE ||
                upload.status == UploadQueueStatus.WAITING_FOR_ACCOUNT
            val canCancel = upload.status == UploadQueueStatus.QUEUED ||
                upload.status == UploadQueueStatus.RETRYABLE_FAILURE ||
                upload.status == UploadQueueStatus.PERMANENT_FAILURE ||
                upload.status == UploadQueueStatus.WAITING_FOR_ACCOUNT
            val canRemove = upload.status == UploadQueueStatus.COMPLETED ||
                upload.status == UploadQueueStatus.CANCELLED
            val quotaExceeded = upload.lastErrorCode == "STORAGE_QUOTA_EXCEEDED"
            if (canRetry || canCancel || canRemove || quotaExceeded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canRetry && !quotaExceeded) {
                        OutlinedButton(
                            onClick = onRetry,
                            enabled = !isActionRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retry")
                        }
                    }
                    if (quotaExceeded) {
                        OutlinedButton(
                            onClick = onManageStorage,
                            enabled = !isActionRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Manage storage")
                        }
                    }
                    if (canCancel) {
                        OutlinedButton(
                            onClick = onCancel,
                            enabled = !isActionRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                    }
                    if (canRemove) {
                        OutlinedButton(
                            onClick = onRemove,
                            enabled = !isActionRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

private fun String.toUploadStatusLabel(): String = when (this) {
    UploadQueueStatus.QUEUED -> "Waiting for connection"
    UploadQueueStatus.UPLOADING -> "Uploading"
    UploadQueueStatus.RETRYABLE_FAILURE -> "Will retry"
    UploadQueueStatus.PERMANENT_FAILURE -> "Needs attention"
    UploadQueueStatus.WAITING_FOR_ACCOUNT -> "Waiting for account"
    UploadQueueStatus.COMPLETED -> "Uploaded"
    UploadQueueStatus.CANCELLED -> "Cancelled"
    else -> "Unknown status"
}

@Composable
fun UploadResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
