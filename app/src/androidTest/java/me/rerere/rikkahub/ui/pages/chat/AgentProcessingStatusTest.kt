package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AgentProcessingStatusComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun phaseReplacementDisplaysTheNewStatus() {
        var status by mutableStateOf<String?>("Routing request")
        composeRule.setContent {
            MaterialTheme {
                AgentProcessingStatus(status = status)
            }
        }

        composeRule.onNodeWithText("Routing request").assertIsDisplayed()
        composeRule.runOnIdle { status = "Running tool" }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Running tool").assertIsDisplayed()
    }

    @Test
    fun clearedStatusKeepsOutgoingTextUntilExitFinishes() {
        composeRule.mainClock.autoAdvance = false
        var status by mutableStateOf<String?>("Running tool")
        composeRule.setContent {
            MaterialTheme {
                AgentProcessingStatus(status = status)
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Running tool").assertIsDisplayed()

        composeRule.runOnIdle { status = null }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Running tool").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithText("Running tool").assertDoesNotExist()
    }
}
