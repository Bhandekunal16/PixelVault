package com.example.ui.screens.images

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.ImageMetadata
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.FullScreenLoading
import com.example.ui.components.StatusBanner
import com.example.ui.viewmodel.ImagesViewModel
import com.example.util.EmailUtils
import com.example.util.FileUtils
import com.example.util.rememberHapticHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesListScreen(
    viewModel: ImagesViewModel,
    baseUrl: String,
    onNavigateToUpload: () -> Unit,
    onImageSelected: (ImageMetadata) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val haptic = rememberHapticHelper()
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadImages(isRefresh = false)
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

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { viewModel.loadImages(isRefresh = true) },
        modifier = Modifier
            .fillMaxSize()
            .testTag("images_list_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (uiState.isMultiSelectMode) "${uiState.selectedIds.size} Selected" else "My Images",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${uiState.images.size} items stored",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isMultiSelectMode) {
                        IconButton(
                            onClick = {
                                haptic.tick()
                                viewModel.selectAll()
                            },
                            modifier = Modifier.testTag("select_all_images_button")
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = "Select All",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                haptic.click()
                                showDeleteConfirmation = true
                            },
                            enabled = uiState.selectedIds.isNotEmpty() && !uiState.isDeleting,
                            modifier = Modifier.testTag("delete_selected_images_button")
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = if (uiState.selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(
                            onClick = {
                                haptic.tick()
                                viewModel.clearSelection()
                            },
                            modifier = Modifier.testTag("exit_multiselect_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel Selection",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                haptic.tick()
                                viewModel.toggleMultiSelectMode()
                            },
                            enabled = uiState.images.isNotEmpty(),
                            modifier = Modifier.testTag("enter_multiselect_button")
                        ) {
                            Icon(
                                Icons.Default.CheckBox,
                                contentDescription = "Select Multiple",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                haptic.click()
                                viewModel.loadImages(isRefresh = true)
                            },
                            modifier = Modifier.testTag("refresh_images_button")
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (uiState.errorMessage != null) {
                StatusBanner(
                    message = uiState.errorMessage!!,
                    isError = true,
                    onDismiss = { viewModel.loadImages(isRefresh = true) }
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

            if (uiState.isLoading && uiState.images.isEmpty()) {
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("images_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = uiState.images,
                        key = { it.effectiveId.ifBlank { it.filename ?: it.hashCode().toString() } }
                    ) { image ->
                        ImageListItemCard(
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
                    }
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
    val context = LocalContext.current
    val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val imageUrl = "${normalizedUrl}image?id=${image.effectiveId}"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("image_item_card_${image.effectiveId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox in selection mode
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.testTag("checkbox_${image.effectiveId}")
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Thumbnail
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = image.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = image.filename ?: "image_${image.effectiveId}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = image.effectiveFormat,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (image.width != null && image.height != null) {
                        Text(
                            text = "${image.width}×${image.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Stored: ${FileUtils.formatBytes(image.storedSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (image.effectiveOptimized) {
                        Text(
                            text = "-${image.savingsPercentage}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
