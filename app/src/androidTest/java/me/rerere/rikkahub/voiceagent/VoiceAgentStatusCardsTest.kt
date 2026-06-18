package me.rerere.rikkahub.voiceagent

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceAgentStatusCardsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun traceIdCardIsRenderedAndCopiesTraceId() {
        var copiedTraceId: String? = null

        composeRule.setContent {
            MaterialTheme {
                Column {
                    VoiceAgentStatusCards(
                        state = VoiceAgentUiState(traceId = "trace-123"),
                        onCopyTraceId = { traceId -> copiedTraceId = traceId },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Trace ID").assertIsDisplayed()
        composeRule.onNodeWithText("trace-123").assertIsDisplayed()
        composeRule.onNodeWithText("Copy").performClick()

        assertEquals("trace-123", copiedTraceId)
    }
}
