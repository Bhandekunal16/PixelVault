package com.example

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.example.ui.screens.upload.TransientQueueNotice
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueNoticeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `queue success notice disappears after four seconds`() {
        val events = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        composeTestRule.setContent {
            com.example.ui.theme.MyApplicationTheme {
                TransientQueueNotice(events)
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            events.tryEmit("Upload retry scheduled.")
        }
        composeTestRule.onNodeWithText("Upload retry scheduled.").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(4_100L)

        composeTestRule.onAllNodesWithText("Upload retry scheduled.").assertCountEquals(0)
    }

    @Test
    fun `queue success notice is not replayed after leaving and returning`() {
        val events = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val isVisible = mutableStateOf(true)
        composeTestRule.setContent {
            com.example.ui.theme.MyApplicationTheme {
                if (isVisible.value) TransientQueueNotice(events)
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            events.tryEmit("Upload retry scheduled.")
        }
        composeTestRule.onNodeWithText("Upload retry scheduled.").assertIsDisplayed()

        composeTestRule.runOnIdle { isVisible.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { isVisible.value = true }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Upload retry scheduled.").assertCountEquals(0)
    }
}
