package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry
import me.rerere.rikkahub.data.ai.agent.subagent.DefaultSubagentRunner
import me.rerere.rikkahub.data.model.AgentApprovalSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpAgentRunRuntimeTest {
    @Test
    fun `approved tools fail closed while automatic tools can start`() = runBlocking {
        val approvedTool = UIMessagePart.Tool(
            toolCallId = "approved-call",
            toolName = "workspace_write_file",
            input = "{}",
            approvalState = ToolApprovalState.Approved,
        )
        val automaticTool = UIMessagePart.Tool(
            toolCallId = "automatic-call",
            toolName = "workspace_read_file",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
        )

        assertFalse(NoOpAgentRunRuntime.approvedFor(null, approvedTool, AgentApprovalSummary()))
        val automaticExecution = NoOpAgentRunRuntime.toolObserved(
            stepId = null,
            tool = automaticTool,
            descriptor = ToolDescriptorRegistry.descriptorFor(automaticTool.toolName),
        )
        assertTrue(NoOpAgentRunRuntime.toolStarted(automaticExecution))
    }

    @Test
    fun `child tool budget counts a stable observed execution only once`() = runBlocking {
        val runtime = DefaultSubagentRunner.BudgetedChildRuntime(NoOpAgentRunRuntime, maxToolCalls = 1)
        val first = UIMessagePart.Tool("call", "workspace_read_file", "{}")
        val second = UIMessagePart.Tool("next", "workspace_read_file", "{}")
        val descriptor = ToolDescriptorRegistry.descriptorFor(first.toolName)

        runtime.toolObserved(null, first, descriptor)
        runtime.toolObserved(null, first, descriptor)

        assertTrue(runCatching { runtime.toolObserved(null, second, descriptor) }.isFailure)
    }
}
