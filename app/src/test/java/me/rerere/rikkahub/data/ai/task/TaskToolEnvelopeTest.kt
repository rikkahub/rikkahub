package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetCap
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.tools.TaskRunDisplayStatus
import me.rerere.rikkahub.ui.components.message.tools.TaskToolUI
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.parseToolOutputContent
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the spawn-tool output envelope (review finding #1) END-TO-END against the live renderer's
 * parse: [buildTaskEnvelope] -> serialized JSON -> [TaskToolUI.taskState]. Before the fix the
 * production tool emitted bare final text and the renderer only ever hit its legacy fallback, so
 * the terminal status / budget counters / interrupted-resume were unreachable. This guards the
 * emit and parse halves of the wire vocabulary against drifting apart (Hyrum's law).
 */
class TaskToolEnvelopeTest {

    private fun result(state: TaskState, text: String = "out", usage: TaskBudgetUsage = TaskBudgetUsage(), maxSteps: Int = 64) =
        TaskRunResult(text = text, state = state, usage = usage, maxSteps = maxSteps)

    private fun parse(result: TaskRunResult) = TaskToolUI.taskState(
        ToolUIContext(
            tool = UIMessagePart.Tool(
                toolCallId = "id",
                toolName = "task",
                input = "{}",
                output = listOf(UIMessagePart.Text(buildTaskEnvelope(result).toString())),
            ),
            arguments = buildJsonObject {},
            content = parseToolOutputContent(
                listOf(UIMessagePart.Text(buildTaskEnvelope(result).toString())),
                isExecuted = true,
            ),
            loading = false,
        )
    )

    @Test
    fun `a succeeded run emits an envelope the renderer reads as Succeeded with budget`() {
        val state = parse(result(TaskState.Succeeded("the answer"), usage = TaskBudgetUsage(steps = 5, tokens = 1200)))
        assertEquals(TaskRunDisplayStatus.Succeeded, state.status)
        assertEquals("the answer", state.summary)
        assertEquals(5, state.steps)
        assertEquals(64, state.maxSteps)
        assertEquals(1200L, state.tokens)
    }

    @Test
    fun `a budget-exhausted run renders as BudgetExhausted, not a bare Done`() {
        val breach = TaskBudgetBreach(cap = TaskBudgetCap.Steps, usage = TaskBudgetUsage(steps = 65))
        val state = parse(result(TaskState.BudgetExhausted(breach), text = "partial", usage = TaskBudgetUsage(steps = 65)))
        assertEquals(TaskRunDisplayStatus.BudgetExhausted, state.status)
        assertEquals(65, state.steps)
    }

    @Test
    fun `a failed run renders as Failed with the error text as summary`() {
        val state = parse(result(TaskState.Failed("boom"), text = "Subagent failed: boom"))
        assertEquals(TaskRunDisplayStatus.Failed, state.status)
        assertTrue(state.summary!!.contains("boom"))
    }

    @Test
    fun `an interrupted run renders resumable`() {
        val state = parse(result(TaskState.Interrupted("got to step 3")))
        assertEquals(TaskRunDisplayStatus.Interrupted, state.status)
        assertTrue(state.resumable)
        assertEquals("got to step 3", state.summary)
    }

    @Test
    fun `a waiting-approval run surfaces the pending child tool`() {
        val state = parse(
            result(TaskState.WaitingApproval(TaskApprovalRequest(childToolCallId = "c1", toolName = "ask_user")))
        )
        assertEquals(TaskRunDisplayStatus.WaitingApproval, state.status)
        assertTrue(state.pendingChildApproval)
        assertEquals("ask_user", state.pendingApprovalToolName)
    }
}
