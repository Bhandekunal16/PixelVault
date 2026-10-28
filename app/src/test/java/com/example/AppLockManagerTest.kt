package com.example

import com.example.security.AppLockManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLockManagerTest {

    private lateinit var lockManager: AppLockManager

    @Before
    fun setup() {
        lockManager = AppLockManager()
    }

    @Test
    fun testInitialState_isUnlocked() {
        assertFalse(lockManager.isLocked.value)
    }

    @Test
    fun testLockNow_setsLocked() {
        lockManager.lockNow()
        assertTrue(lockManager.isLocked.value)
    }

    @Test
    fun testUnlock_clearsLock() {
        lockManager.lockNow()
        assertTrue(lockManager.isLocked.value)

        lockManager.unlock()
        assertFalse(lockManager.isLocked.value)
    }

    @Test
    fun testReset_clearsLock() {
        lockManager.lockNow()
        lockManager.reset()
        assertFalse(lockManager.isLocked.value)
    }

    @Test
    fun testForegrounding_immediateTimeout_locksApp() {
        val backgroundTime = 1000L
        lockManager.onAppBackgrounded(backgroundTime)

        // Return to foreground 100ms later with 0s (immediate) timeout
        lockManager.onAppForegrounded(
            isLockEnabled = true,
            isLoggedIn = true,
            timeoutSeconds = 0L,
            timestamp = 1100L
        )

        assertTrue(lockManager.isLocked.value)
    }

    @Test
    fun testForegrounding_withinTimeout_remainsUnlocked() {
        val backgroundTime = 1000L
        lockManager.onAppBackgrounded(backgroundTime)

        // Return to foreground 10 seconds later with 30s timeout (10000ms < 30000ms)
        lockManager.onAppForegrounded(
            isLockEnabled = true,
            isLoggedIn = true,
            timeoutSeconds = 30L,
            timestamp = backgroundTime + 10_000L
        )

        assertFalse(lockManager.isLocked.value)
    }

    @Test
    fun testForegrounding_afterTimeout_locksApp() {
        val backgroundTime = 1000L
        lockManager.onAppBackgrounded(backgroundTime)

        // Return to foreground 35 seconds later with 30s timeout (35000ms >= 30000ms)
        lockManager.onAppForegrounded(
            isLockEnabled = true,
            isLoggedIn = true,
            timeoutSeconds = 30L,
            timestamp = backgroundTime + 35_000L
        )

        assertTrue(lockManager.isLocked.value)
    }

    @Test
    fun testForegrounding_lockDisabled_doesNotLock() {
        val backgroundTime = 1000L
        lockManager.onAppBackgrounded(backgroundTime)

        // Return to foreground after a long duration, but lock is disabled
        lockManager.onAppForegrounded(
            isLockEnabled = false,
            isLoggedIn = true,
            timeoutSeconds = 0L,
            timestamp = backgroundTime + 60_000L
        )

        assertFalse(lockManager.isLocked.value)
    }

    @Test
    fun testForegrounding_notLoggedIn_doesNotLock() {
        val backgroundTime = 1000L
        lockManager.onAppBackgrounded(backgroundTime)

        // Return to foreground after a long duration, but user is logged out
        lockManager.onAppForegrounded(
            isLockEnabled = true,
            isLoggedIn = false,
            timeoutSeconds = 0L,
            timestamp = backgroundTime + 60_000L
        )

        assertFalse(lockManager.isLocked.value)
    }
}
