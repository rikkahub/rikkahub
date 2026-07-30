package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTokenUsageTest {
    @Test
    fun `usage is shown only after generation and tools finish`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("done")),
            usage = TokenUsage(promptTokens = 100),
        )

        assertFalse(shouldShowTokenUsage(message, loading = true))
        assertTrue(shouldShowTokenUsage(message, loading = false))
    }

    @Test
    fun `usage is hidden for unresolved tools`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "search",
                    input = "{}",
                )
            ),
            usage = TokenUsage(promptTokens = 100),
        )

        assertFalse(shouldShowTokenUsage(message, loading = false))
    }

    @Test
    fun `usage is shown after all tools execute`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result")),
                )
            ),
            usage = TokenUsage(promptTokens = 100),
        )

        assertTrue(shouldShowTokenUsage(message, loading = false))
    }
}
