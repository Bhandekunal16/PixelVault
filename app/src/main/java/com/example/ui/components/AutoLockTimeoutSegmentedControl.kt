package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AutoLockSegmentUnselectedBackground
import com.example.ui.theme.AutoLockSegmentUnselectedLabel

private val autoLockTimeoutOptions = listOf(
    0L to "Immediately",
    30L to "30 sec",
    60L to "1 min",
    300L to "5 min"
)

@Composable
fun AutoLockTimeoutSegmentedControl(
    selectedTimeoutSeconds: Long,
    onTimeoutSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val unselectedBackground = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        AutoLockSegmentUnselectedBackground
    }
    val selectedBackground = MaterialTheme.colorScheme.primary
    val unselectedTextColor = if (isDark) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        AutoLockSegmentUnselectedLabel
    }
    val selectedTextColor = MaterialTheme.colorScheme.onPrimary

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        autoLockTimeoutOptions.forEach { (seconds, label) ->
            val isSelected = selectedTimeoutSeconds == seconds
            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected) selectedBackground else unselectedBackground,
                animationSpec = tween(durationMillis = 180),
                label = "auto_lock_segment_background"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) selectedTextColor else unselectedTextColor,
                animationSpec = tween(durationMillis = 180),
                label = "auto_lock_segment_text"
            )

            Surface(
                onClick = { onTimeoutSelected(seconds) },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .semantics {
                        role = Role.Tab
                        selected = isSelected
                    }
                    .testTag("timeout_option_$seconds"),
                shape = RoundedCornerShape(8.dp),
                color = backgroundColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        ),
                        color = textColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
