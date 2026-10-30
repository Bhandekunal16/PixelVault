package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.OnSuccessGreenLight
import com.example.ui.theme.SuccessGreen

@Composable
fun CompressionSavingsBadge(
    visible: Boolean,
    percentage: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.widthIn(min = 52.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        if (visible) {
            val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val badgeBackground = if (isDark) {
                SuccessGreen.copy(alpha = 0.22f)
            } else {
                SuccessGreen.copy(alpha = 0.14f)
            }
            val textColor = if (isDark) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                OnSuccessGreenLight
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeBackground
            ) {
                Text(
                    text = "-$percentage%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
