package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContinuationTest {
    @Test
    fun `continue should block unresolved tool calls`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "search_web",
                    input = """{"query":"latest news"}""",
                )
            )
        )

        assertTrue(message.hasBlockingToolsForContinuation())
    }

    @Test
    fun `continue should block pending approval tools`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "termux_exec",
                    input = """{"command":"ls"}""",
                    approvalState = ToolApprovalState.Pending,
                )
            )
        )

        assertTrue(message.hasBlockingToolsForContinuation())
    }

    @Test
    fun `continue should allow tools that are already resumable or executed`() {
        val approvedToolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "ask_user",
                    input = """{"question":"continue?"}""",
                    approvalState = ToolApprovalState.Approved,
                )
            )
        )
        val executedToolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "tool-2",
                    toolName = "search_web",
                    input = """{"query":"rikkahub"}""",
                    output = listOf(UIMessagePart.Text("done")),
                )
            )
        )

        assertFalse(approvedToolMessage.hasBlockingToolsForContinuation())
        assertFalse(executedToolMessage.hasBlockingToolsForContinuation())
    }
}
