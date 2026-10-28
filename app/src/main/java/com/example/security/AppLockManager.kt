package com.example.security

import android.app.Activity
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State coordinator managing application locking state, background timeout tracking,
 * and Android Window security flags (FLAG_SECURE) to prevent screenshots or recents task leak.
 */
class AppLockManager {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var lastBackgroundTimestamp: Long = 0L

    /**
     * Called when the application enters the background (e.g. onStop / onPause).
     */
    fun onAppBackgrounded(timestamp: Long = System.currentTimeMillis()) {
        lastBackgroundTimestamp = timestamp
    }

    /**
     * Called when the application returns to foreground (e.g. onStart / onResume).
     * Compares elapsed background duration against the user's configured timeout.
     */
    fun onAppForegrounded(
        isLockEnabled: Boolean,
        isLoggedIn: Boolean,
        timeoutSeconds: Long,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (!isLockEnabled || !isLoggedIn) {
            // App lock not applicable
            return
        }

        if (_isLocked.value) {
            // Already locked
            return
        }

        if (lastBackgroundTimestamp > 0) {
            val elapsedMs = timestamp - lastBackgroundTimestamp
            val timeoutMs = timeoutSeconds * 1000L
            if (elapsedMs >= timeoutMs) {
                _isLocked.value = true
            }
        }
    }

    /**
     * Unlocks the session upon verified biometric or device credential authentication.
     */
    fun unlock() {
        _isLocked.value = false
        lastBackgroundTimestamp = 0L
    }

    /**
     * Manually triggers an immediate lock of the application.
     */
    fun lockNow() {
        _isLocked.value = true
    }

    /**
     * Resets lock state (e.g. upon user logging out).
     */
    fun reset() {
        _isLocked.value = false
        lastBackgroundTimestamp = 0L
    }

    /**
     * Configures WindowManager.LayoutParams.FLAG_SECURE dynamically.
     * Prevents content capture, screenshots, and task switcher preview leaks when locked
     * or when app lock protection is enabled in release builds.
     *
     * Note: In debug builds and streaming emulator environments (such as AI Studio's
     * browser-streamed emulator), FLAG_SECURE causes Android's SurfaceFlinger to blank the screen,
     * producing a completely black frame in the browser stream. Therefore, FLAG_SECURE is
     * disabled in debug builds so the emulator display renders normally.
     */
    fun updateWindowSecurity(activity: Activity, secure: Boolean) {
        try {
            if (secure && !com.example.BuildConfig.DEBUG) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (_: Exception) {
            // Window may not be attached in certain test scenarios
        }
    }
}
