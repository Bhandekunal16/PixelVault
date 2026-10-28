package com.example.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

class HapticHelper(
    private val composeHaptic: HapticFeedback?,
    private val view: View?,
    private val vibrator: Vibrator?
) {
    /**
     * Subtle click feedback for standard buttons (Upload, Refresh, Log In, Confirm).
     */
    fun click() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view != null) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } catch (_: Exception) {
            composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /**
     * Ultra-subtle tick for segmented selections, chips, and format toggles.
     */
    fun tick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && view != null) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } catch (_: Exception) {
            composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /**
     * Light crisp feedback for switches and toggles.
     */
    fun toggle() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && view != null) {
                view.performHapticFeedback(HapticFeedbackConstants.TOGGLE_ON)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } catch (_: Exception) {
            composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /**
     * Smooth subtle feedback for bottom navigation transitions.
     */
    fun navigation() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view != null) {
                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } catch (_: Exception) {
            composeHaptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /**
     * Gentle positive feedback for completed operations (e.g. upload done, unlock).
     */
    fun success() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view != null) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            } else {
                composeHaptic?.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        } catch (_: Exception) {
            composeHaptic?.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

@Composable
fun rememberHapticHelper(): HapticHelper {
    val composeHaptic = LocalHapticFeedback.current
    val view = LocalView.current
    val context = LocalContext.current
    return remember(composeHaptic, view, context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        HapticHelper(composeHaptic, view, vibrator)
    }
}
