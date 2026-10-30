package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private val autoLockTimeoutOptions = listOf(
    SegmentedOption(0L, "Immediately", "timeout_option_0"),
    SegmentedOption(30L, "30 sec", "timeout_option_30"),
    SegmentedOption(60L, "1 min", "timeout_option_60"),
    SegmentedOption(300L, "5 min", "timeout_option_300")
)

@Composable
fun AutoLockTimeoutSegmentedControl(
    selectedTimeoutSeconds: Long,
    onTimeoutSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedControl(
        options = autoLockTimeoutOptions,
        selectedValue = selectedTimeoutSeconds,
        onOptionSelected = onTimeoutSelected,
        modifier = modifier
    )
}
