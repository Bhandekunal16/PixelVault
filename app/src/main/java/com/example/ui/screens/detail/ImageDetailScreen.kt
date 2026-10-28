package com.example.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.example.ui.components.StatusBanner
import com.example.ui.theme.ErrorRed
import com.example.ui.viewmodel.DetailViewModel
import com.example.util.EmailUtils
import com.example.util.FileUtils
import com.example.util.rememberHapticHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDetailScreen(
    metadata: ImageMetadata,
    baseUrl: String,
    viewModel: DetailViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val haptic = rememberHapticHelper()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val imageBytesUrl = "${normalizedUrl}image?id=${metadata.effectiveId}"

    LaunchedEffect(metadata.effectiveId) {
        viewModel.setMetadata(metadata)
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = "Delete Image?",
            message = "Are you sure you want to delete '${metadata.filename ?: metadata.effectiveId}'? This operation cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteImage {
                    onNavigateBack()
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = metadata.filename ?: "Image Detail",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.navigation()
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("detail_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptic.click()
                            viewModel.fetchImageBytes(metadata.effectiveId)
                        },
                        modifier = Modifier.testTag("detail_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    IconButton(
                        onClick = {
                            haptic.click()
                            showDeleteConfirm = true
                        },
                        modifier = Modifier.testTag("detail_delete_button")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(16.dp)
                .testTag("image_detail_screen")
        ) {
            if (uiState.errorMessage != null) {
                StatusBanner(message = uiState.errorMessage!!, isError = true)
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (uiState.infoMessage != null) {
                StatusBanner(
                    message = uiState.infoMessage!!,
                    isSuccess = true,
                    onDismiss = { viewModel.clearInfoMessage() }
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Image Preview Container with Solid Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("detail_image_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val imageSource: Any? = uiState.imageBytes ?: imageBytesUrl
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageSource)
                            .crossfade(true)
                            .build(),
                        contentDescription = metadata.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .testTag("detail_image_view")
                    )

                    if (uiState.isLoadingBytes) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        haptic.click()
                        viewModel.downloadImage(context)
                    },
                    enabled = !uiState.isDownloading && (uiState.imageBytes != null),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("download_image_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (uiState.isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Saving...", color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save to Downloads", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                OutlinedButton(
                    onClick = {
                        haptic.click()
                        showDeleteConfirm = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("detail_delete_action_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = ErrorRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Image", color = ErrorRed)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Metadata Card with Solid Surface and Crisp Border
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("metadata_detail_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Technical Metadata",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    MetadataItem("Filename", metadata.filename ?: "Untitled")
                    MetadataItem("Image ID", metadata.effectiveId)
                    MetadataItem("Format / MIME", "${metadata.effectiveFormat} (${metadata.mimetype ?: "unknown"})")

                    if (metadata.width != null && metadata.height != null) {
                        MetadataItem("Dimensions", "${metadata.width} × ${metadata.height} pixels")
                    }

                    MetadataItem("Original Size", FileUtils.formatBytes(metadata.originalSize))
                    MetadataItem("Stored Size", FileUtils.formatBytes(metadata.storedSize))

                    if (metadata.savingsBytes > 0) {
                        MetadataItem("Storage Savings", "${FileUtils.formatBytes(metadata.savingsBytes)} (${metadata.savingsPercentage}%)")
                    }

                    MetadataItem(
                        "Optimization",
                        if (metadata.effectiveOptimized) "Optimized" else "Standard format"
                    )

                    if (!metadata.checksum.isNullOrBlank()) {
                        MetadataItem("Checksum (SHA)", metadata.checksum!!)
                    }

                    if (metadata.effectiveDate.isNotBlank()) {
                        MetadataItem("Upload Date", metadata.effectiveDate)
                    }

                    if (!metadata.email.isNullOrBlank()) {
                        MetadataItem("Owner Account", EmailUtils.formatDisplayGmail(metadata.email))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun MetadataItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
