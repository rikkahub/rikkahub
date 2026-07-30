package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AgentRunCenterTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeCardExposesStatusAndOpenAction() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunActiveCard(
                    run = AgentRunPresentation(
                        runId = "run",
                        status = "运行中",
                        statusDescription = null,
                        model = "model",
                        mode = "AGENT",
                        runtimeVersion = null,
                        maxSteps = 4,
                        completedSteps = 1,
                        currentStep = "步骤 2 · tool",
                        waitingReason = null,
                        createdAt = 0,
                        duration = "1.00s",
                        failureCategory = null,
                        timeline = emptyList(),
                    ),
                    onOpen = {},
                )
            }
        }

        composeRule.onNodeWithText("运行 · 运行中").assertIsDisplayed()
    }
}
