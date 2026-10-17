package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompressionSummaryTest {
    private fun reply(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    @Test
    fun `plain text reply is the summary`() {
        val summary = reply(UIMessagePart.Text("  [Summary of previous conversation]\n## User Goals\n...  "))
            .compressionSummaryOrNull()

        assertEquals("[Summary of previous conversation]\n## User Goals\n...", summary)
    }

    @Test
    fun `reply with a tool call is rejected even if it has text`() {
        val summary = reply(
            UIMessagePart.Text("Let me check the file first."),
            UIMessagePart.Tool(toolCallId = "call_1", toolName = "workspace_shell", input = "{}"),
        ).compressionSummaryOrNull()

        assertNull(summary)
    }

    @Test
    fun `inline think tags are stripped`() {
        val summary = reply(UIMessagePart.Text("<think>planning the summary</think>\n[Summary of previous conversation]"))
            .compressionSummaryOrNull()

        assertEquals("[Summary of previous conversation]", summary)
    }

    @Test
    fun `reasoning parts are not part of the summary`() {
        val summary = reply(
            UIMessagePart.Reasoning(reasoning = "planning the summary"),
            UIMessagePart.Text("[Summary of previous conversation]"),
        ).compressionSummaryOrNull()

        assertEquals("[Summary of previous conversation]", summary)
    }

    @Test
    fun `reply without text gives a blank summary`() {
        assertEquals("", reply().compressionSummaryOrNull())
    }
}
