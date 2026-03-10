package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerTermuxApprovalTest {
    @Test
    fun `evaluatePendingToolApprovals should only inspect current write stdin payload`() {
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
            blacklistRules = listOf("rm"),
        )

        assertTrue(result.hasPendingApproval)
        assertEquals(ToolApprovalState.Pending, result.tools[0].approvalState)
        assertEquals(ToolApprovalState.Auto, result.tools[1].approvalState)
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
