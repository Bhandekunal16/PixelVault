package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.StorageUsageEntity
import com.example.util.FileUtils

private const val USAGE_FRESHNESS_MILLIS = 2 * 60 * 1000L

@Composable
fun StorageUsageCard(
    usage: StorageUsageEntity?,
    isRefreshing: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Cloud storage",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh cloud storage usage"
                        )
                    }
                }
            }

            if (usage == null) {
                Text(
                    text = errorMessage ?: "Loading cloud storage usage…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (errorMessage != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                return@Column
            }

            val warningColor = when (usage.warningLevel) {
                "info" -> MaterialTheme.colorScheme.tertiary
                "warning", "full" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            val warningText = when (usage.warningLevel) {
                "info" -> "Storage usage is at ${usage.usagePercentage}%."
                "warning" -> "Storage almost full. Delete unwanted images before uploading more."
                "full" -> "Storage full. Delete unwanted images to make room for uploads."
                else -> "Cloud storage is available."
            }
            val isStale = System.currentTimeMillis() - usage.fetchedAt >= USAGE_FRESHNESS_MILLIS

            Text(
                text = "${FileUtils.formatBytes(usage.usedBytes)} used of ${FileUtils.formatBytes(usage.quotaBytes)}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            LinearProgressIndicator(
                progress = { usage.usagePercentage.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            usage.usagePercentage.coerceIn(0, 100) / 100f,
                            0f..1f
                        )
                    },
                color = warningColor
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${usage.usagePercentage}% · ${usage.imageCount} images",
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor
                )
                Text(
                    text = "${FileUtils.formatBytes(usage.availableBytes)} available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = warningText,
                style = MaterialTheme.typography.bodySmall,
                color = warningColor
            )
            Spacer(modifier = Modifier.height(0.dp))
            if (isStale || errorMessage != null || usage.lastSyncError != null) {
                Text(
                    text = "Showing last confirmed usage; it may be outdated.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (errorMessage != null || usage.lastSyncError != null) {
                Text(
                    text = errorMessage ?: usage.lastSyncError.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
