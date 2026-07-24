package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MessageTimeVariablesTest {
    @Test
    fun `time variables come from message creation time`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("hello")),
            createdAt = LocalDateTime(2026, 7, 24, 9, 30, 15),
        )

        val variables = message.timeVariables(Locale.US)

        assertEquals("Jul 24, 2026", variables.date)
        assertEquals("9:30:15\u202fAM", variables.time)
    }
}
