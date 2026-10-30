package com.example.ui.screens.images

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.ImageMetadata
import com.example.ui.components.AppToolbarIconButton
import com.example.ui.components.CompressionSavingsBadge
import com.example.ui.components.PendingUploadBanner
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.FullScreenLoading
import com.example.ui.components.StatusBanner
import com.example.ui.theme.AppDimens
import com.example.ui.viewmodel.ImagesViewModel
import com.example.util.EmailUtils
import com.example.util.FileUtils
import com.example.util.rememberHapticHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesListScreen(
    viewModel: ImagesViewModel,
    baseUrl: String,
    pendingUploadCount: Int = 0,
    onNavigateToUpload: () -> Unit,
    onImageSelected: (ImageMetadata) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val haptic = rememberHapticHelper()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val allSelected = uiState.images.isNotEmpty() &&
        uiState.selectedIds.size == uiState.images.count { it.effectiveId.isNotBlank() }

    LaunchedEffect(Unit) {
        viewModel.syncIfStale()
    }

    if (showDeleteConfirmation) {
        ConfirmationDialog(
            title = "Delete ${uiState.selectedIds.size} Image${if (uiState.selectedIds.size > 1) "s" else ""}?",
            message = "This will permanently remove the selected images from your vault.",
            confirmLabel = "Delete Selected",
            onConfirm = {
                showDeleteConfirmation = false
                viewModel.deleteSelected()
            },
            onDismiss = { showDeleteConfirmation = false }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("images_list_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (uiState.selectedIds.isNotEmpty()) {
                ImagesSelectionBottomBar(
                    selectedCount = uiState.selectedIds.size,
                    canDownload = uiState.selectedIds.size == 1,
                    isBusy = uiState.isDeleting || uiState.isPerformingListAction,
                    onDelete = {
                        haptic.click()
                        showDeleteConfirmation = true
                    },
                    onDownload = {
                        haptic.click()
                        viewModel.downloadSelectedImage(context)
                    }
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshImages() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                ImagesListHeader(
                    title = when {
                        uiState.isMultiSelectMode && uiState.selectedIds.isNotEmpty() ->
                            "${uiState.selectedIds.size} selected"
                        uiState.isMultiSelectMode -> "Select images"
                        else -> "My Images"
                    },
                    storedCount = uiState.images.size,
                    showStoredCount = !uiState.isMultiSelectMode || uiState.selectedIds.isEmpty(),
                    isMultiSelectMode = uiState.isMultiSelectMode,
                    canSelect = uiState.images.isNotEmpty(),
                    isSyncing = uiState.isSyncing,
                    allSelected = allSelected,
                    onEnterMultiSelect = {
                        haptic.tick()
                        viewModel.toggleMultiSelectMode()
                    },
                    onSelectAll = {
                        haptic.tick()
                        viewModel.selectAll()
                    },
                    onExitMultiSelect = {
                        haptic.tick()
                        viewModel.clearSelection()
                    },
                    onRefresh = {
                        haptic.click()
                        viewModel.refreshImages()
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (pendingUploadCount > 0) {
                    PendingUploadBanner(
                        pendingUploadCount = pendingUploadCount,
                        onClick = onNavigateToUpload,
                        testTag = "images_pending_uploads"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (uiState.errorMessage != null) {
                    StatusBanner(message = uiState.errorMessage!!, isError = true)
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (uiState.syncErrorMessage != null) {
                    StatusBanner(
                        message = "Showing saved images. Sync failed: ${uiState.syncErrorMessage}",
                        isWarning = true,
                        onDismiss = { viewModel.clearSyncError() }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (uiState.deleteSuccessMessage != null) {
                    StatusBanner(
                        message = uiState.deleteSuccessMessage!!,
                        isSuccess = true,
                        onDismiss = { viewModel.clearDeleteMessage() }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (uiState.actionInfoMessage != null) {
                    StatusBanner(
                        message = uiState.actionInfoMessage!!,
                        isSuccess = true,
                        onDismiss = { viewModel.clearActionMessage() }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if ((uiState.isLoading || uiState.isSyncing) && uiState.images.isEmpty()) {
                    FullScreenLoading(text = "Loading your images...")
                } else if (uiState.images.isEmpty()) {
                    val displayEmail = EmailUtils.formatDisplayGmail(uiState.userEmail, fallback = "your account")
                    EmptyStateCard(
                        title = "No Images Found",
                        description = "No image records found for $displayEmail. Upload images to populate your vault.",
                        icon = Icons.Default.Image,
                        actionLabel = "Upload Image",
                        onAction = onNavigateToUpload,
                        modifier = Modifier.padding(top = 40.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("images_lazy_column"),
                            contentPadding = PaddingValues(
                                bottom = if (uiState.selectedIds.isNotEmpty()) 8.dp else 24.dp
                            )
                        ) {
                            itemsIndexed(
                                items = uiState.images,
                                key = { _, image ->
                                    image.effectiveId.ifBlank { image.effectiveFilename ?: image.hashCode().toString() }
                                }
                            ) { index, image ->
                                ImageListItem(
                                    image = image,
                                    baseUrl = baseUrl,
                                    isSelected = uiState.selectedIds.contains(image.effectiveId),
                                    isMultiSelectMode = uiState.isMultiSelectMode,
                                    onToggleSelection = {
                                        haptic.tick()
                                        viewModel.toggleSelection(image.effectiveId)
                                    },
                                    onClick = {
                                        if (uiState.isMultiSelectMode) {
                                            haptic.tick()
                                            viewModel.toggleSelection(image.effectiveId)
                                        } else {
                                            haptic.click()
                                            onImageSelected(image)
                                        }
                                    }
                                )
                                if (index < uiState.images.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagesListHeader(
    title: String,
    storedCount: Int,
    showStoredCount: Boolean,
    isMultiSelectMode: Boolean,
    canSelect: Boolean,
    isSyncing: Boolean,
    allSelected: Boolean,
    onEnterMultiSelect: () -> Unit,
    onSelectAll: () -> Unit,
    onExitMultiSelect: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            if (showStoredCount) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (storedCount == 1) "1 image stored" else "$storedCount images stored",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelectMode) {
                AppToolbarIconButton(
                    onClick = onSelectAll,
                    enabled = canSelect,
                    icon = Icons.Default.SelectAll,
                    contentDescription = if (allSelected) "Clear selection" else "Select all",
                    testTag = "select_all_images_button"
                )
                AppToolbarIconButton(
                    onClick = onExitMultiSelect,
                    icon = Icons.Default.Close,
                    contentDescription = "Cancel selection",
                    testTag = "exit_multiselect_button"
                )
            } else {
                AppToolbarIconButton(
                    onClick = onEnterMultiSelect,
                    enabled = canSelect,
                    icon = Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = "Select multiple",
                    testTag = "enter_multiselect_button"
                )
            }
            AppToolbarIconButton(
                onClick = onRefresh,
                enabled = !isSyncing,
                icon = Icons.Default.Refresh,
                contentDescription = "Refresh",
                testTag = "refresh_images_button",
                showProgress = isSyncing
            )
        }
    }
}

@Composable
private fun ImagesSelectionBottomBar(
    selectedCount: Int,
    canDownload: Boolean,
    isBusy: Boolean,
    onDelete: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("images_selection_bottom_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (selectedCount == 1) "1 image selected" else "$selectedCount images selected",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canDownload) {
                    IconButton(
                        onClick = onDownload,
                        enabled = !isBusy,
                        modifier = Modifier
                            .size(AppDimens.minTouchTarget)
                            .testTag("download_selected_images_button")
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    onClick = onDelete,
                    enabled = !isBusy,
                    modifier = Modifier
                        .size(AppDimens.minTouchTarget)
                        .testTag("delete_selected_images_button")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun ImageListItemCard(
    image: ImageMetadata,
    baseUrl: String,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit
) {
    ImageListItem(
        image = image,
        baseUrl = baseUrl,
        isSelected = isSelected,
        isMultiSelectMode = isMultiSelectMode,
        onToggleSelection = onToggleSelection,
        onClick = onClick
    )
}

@Composable
private fun ImageListItem(
    image: ImageMetadata,
    baseUrl: String,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val imageUrl = "${normalizedUrl}image?id=${image.effectiveId}"

    val rowBackground = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.listItemMinHeight)
            .background(rowBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("image_item_card_${image.effectiveId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.outline,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .size(24.dp)
                    .testTag("checkbox_${image.effectiveId}")
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Box(
            modifier = Modifier
                .size(AppDimens.thumbnailSize)
                .clip(RoundedCornerShape(AppDimens.thumbnailRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = image.effectiveFilename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = image.effectiveFilename ?: "image_${image.effectiveId}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildImageMetaLine(image),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        CompressionSavingsBadge(
            visible = image.effectiveOptimized && image.savingsPercentage > 0,
            percentage = image.savingsPercentage
        )
    }
}

private fun buildImageMetaLine(image: ImageMetadata): String {
    val parts = mutableListOf<String>()
    parts += image.effectiveFormat
    if (image.width != null && image.height != null) {
        parts += "${image.width}×${image.height}"
    }
    parts += FileUtils.formatBytes(image.storedSize)
    return parts.joinToString(" · ")
}
