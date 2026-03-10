package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.termux.TermuxPtyInputBufferRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerTermuxApprovalTest {
    @Test
    fun `evaluatePendingToolApprovals should combine sequential write stdin commands in one batch`() {
        TermuxPtyInputBufferRegistry.clearForTests()
        TermuxPtyInputBufferRegistry.registerSession("session-1")

        val tools = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = "write_stdin",
                input = """{"session_id":"session-1","chars":"rm"}""",
            ),
            UIMessagePart.Tool(
                toolCallId = "call-2",
                toolName = "write_stdin",
                input = """{"session_id":"session-1","chars":" -rf /\n"}""",
            ),
        )

        val result = evaluatePendingToolApprovals(
            tools = tools,
            toolsInternal = listOf(writeStdinToolDefinition()),
            blacklistRules = listOf("rm -rf"),
        )

        assertTrue(result.hasPendingApproval)
        assertEquals(ToolApprovalState.Auto, result.tools[0].approvalState)
        assertEquals(ToolApprovalState.Pending, result.tools[1].approvalState)

        TermuxPtyInputBufferRegistry.clearForTests()
    }

    private fun writeStdinToolDefinition(): Tool {
        return Tool(
            name = "write_stdin",
            description = "",
            needsApproval = false,
            execute = { emptyList() },
        )
    }
}
