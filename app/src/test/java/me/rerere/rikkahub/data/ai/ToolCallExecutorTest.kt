package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallExecutorTest {
    private val executor = ToolCallExecutor(Json)

    @Test
    fun `approval decision marks auto call pending`() {
        val call = toolCall(ToolApprovalState.Auto)
        val definition = toolDefinition(needsApproval = true)

        val decision = executor.prepareApproval(listOf(call), listOf(definition))

        assertTrue(decision.tools.single().approvalState is ToolApprovalState.Pending)
        assertEquals(listOf("call-1"), decision.pendingIds)
    }

    @Test
    fun `denied and answered tools become executed outputs`() = runTest {
        val denied = executor.execute(
            listOf(toolCall(ToolApprovalState.Denied("no"))),
            listOf(toolDefinition()),
        ).single()
        val answered = executor.execute(
            listOf(toolCall(ToolApprovalState.Answered("answer"))),
            listOf(toolDefinition()),
        ).single()

        assertTrue((denied.output.single() as UIMessagePart.Text).text.contains("tool_denied"))
        assertEquals("answer", (answered.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `tool failure returns stable error without stack trace`() = runTest {
        val failed = executor.execute(
            listOf(toolCall(ToolApprovalState.Approved)),
            listOf(toolDefinition(throws = true)),
        ).single()
        val output = (failed.output.single() as UIMessagePart.Text).text

        assertTrue(output.contains("tool_execution_failed"))
        assertFalse(output.contains("ToolCallExecutorTest"))
        assertFalse(output.contains("at me.rerere"))
    }

    private fun toolCall(approval: ToolApprovalState) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "test_tool",
        input = "{}",
        approvalState = approval,
    )

    private fun toolDefinition(needsApproval: Boolean = false, throws: Boolean = false) = Tool(
        name = "test_tool",
        description = "test",
        parameters = { InputSchema.Obj(buildJsonObject { }) },
        needsApproval = { needsApproval },
        execute = {
            if (throws) error("secret stack detail")
            listOf(UIMessagePart.Text("ok"))
        },
    )
}
