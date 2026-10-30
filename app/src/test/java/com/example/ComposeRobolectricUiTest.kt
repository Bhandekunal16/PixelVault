package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatusBanner
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeRobolectricUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `status banner displays error message with proper tag`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                StatusBanner(
                    message = "Validation failed: invalid Gmail",
                    isError = true
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Validation failed: invalid Gmail").assertIsDisplayed()
    }

    @Test
    fun `empty state card triggers action callback`() {
        var actionClicked = false
        composeTestRule.setContent {
            MyApplicationTheme {
                EmptyStateCard(
                    title = "No Images Found",
                    description = "Upload images to populate your vault.",
                    icon = Icons.Default.Image,
                    actionLabel = "Upload Now",
                    onAction = { actionClicked = true }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("empty_state_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("empty_state_action_button").performClick()
        assertTrue(actionClicked)
    }

    @Test
    fun `confirmation dialog triggers confirm callback on delete action`() {
        var confirmed = false
        composeTestRule.setContent {
            MyApplicationTheme {
                ConfirmationDialog(
                    title = "Delete Selected?",
                    message = "This operation is irreversible.",
                    confirmLabel = "Confirm Delete",
                    onConfirm = { confirmed = true },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_dialog_button").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `account info row displays read-only formatted gmail address without trailing noise`() {
        val rawEmail = "bhandekunal16@gmail.com_trailingdata123"
        val formattedEmail = com.example.util.EmailUtils.formatDisplayGmail(rawEmail)
        composeTestRule.setContent {
            MyApplicationTheme {
                com.example.ui.components.AccountInfoRow(
                    label = "Email",
                    value = formattedEmail
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Email").assertIsDisplayed()
        composeTestRule.onNodeWithText("bhandekunal16@gmail.com").assertIsDisplayed()
    }
}
