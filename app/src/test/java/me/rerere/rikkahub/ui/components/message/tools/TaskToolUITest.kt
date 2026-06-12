package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM guard for [TaskToolUI.taskState], the single source of truth the live task renderer
 * draws from (SPEC.md M5). The renderer reads live task state out of the EXISTING
 * `UIMessagePart.Tool` output JSON — no new `UIMessagePart` subtype — so this test pins the
 * parse, not the Compose render.
 *
 * Two output shapes must both be handled, because the parse is the one place a wire-format drift
 * (Hyrum's law) would surface: a structured `{task:{...}}` envelope (the live mirror the spawn
 * tool emits) AND a bare final-text string (the legacy spawn output, and the fallback whenever
 * the structured envelope is absent or malformed). A renderer that crashed on the legacy shape
 * would break every pre-M5 transcript.
 */
class TaskToolUITest {

    private fun context(output: List<UIMessagePart>, arguments: JsonElement = buildJsonObject {}, loading: Boolean = false): ToolUIContext =
        ToolUIContext(
            tool = UIMessagePart.Tool(
                toolCallId = "id",
                toolName = "task",
                input = "{}",
                output = output,
            ),
            arguments = arguments,
            content = parseToolOutputContent(output, isExecuted = output.isNotEmpty()),
            loading = loading,
        )

    private fun textOutput(text: String): List<UIMessagePart> = listOf(UIMessagePart.Text(text))

    @Test
    fun `task is registered in the registry`() {
        assertEquals("task", ToolUIRegistry.resolve("task").toolName)
    }

    @Test
    fun `a structured task envelope parses status, budget counters and summary`() {
        val json = buildJsonObject {
            put("task", buildJsonObject {
                put("status", "running")
                put("summary", "halfway through the report")
                put("budget", buildJsonObject {
                    put("steps", 7)
                    put("maxSteps", 64)
                    put("tokens", 1234)
                })
            })
        }
        val state = TaskToolUI.taskState(context(textOutput(json.toString())))

        assertEquals(TaskRunDisplayStatus.Running, state.status)
        assertEquals("halfway through the report", state.summary)
        assertEquals(7, state.steps)
        assertEquals(64, state.maxSteps)
        assertEquals(1234L, state.tokens)
        assertFalse(state.pendingChildApproval)
        assertFalse(state.resumable)
    }

    @Test
    fun `a pending child approval surfaces to the parent and the tool name shows`() {
        val json = buildJsonObject {
            put("task", buildJsonObject {
                put("status", "waiting_approval")
                put("pendingApproval", buildJsonObject {
                    put("toolName", "ask_user")
                    put("childToolCallId", "child-1")
                })
            })
        }
        val state = TaskToolUI.taskState(context(textOutput(json.toString())))

        assertEquals(TaskRunDisplayStatus.WaitingApproval, state.status)
        assertTrue(state.pendingChildApproval)
        assertEquals("ask_user", state.pendingApprovalToolName)
    }

    @Test
    fun `an interrupted task is resumable`() {
        val json = buildJsonObject {
            put("task", buildJsonObject {
                put("status", "interrupted")
                put("summary", "got through step 3")
            })
        }
        val state = TaskToolUI.taskState(context(textOutput(json.toString())))

        assertEquals(TaskRunDisplayStatus.Interrupted, state.status)
        assertTrue(state.resumable)
        assertEquals("got through step 3", state.summary)
    }

    @Test
    fun `a succeeded task is not resumable and has no pending approval`() {
        val json = buildJsonObject {
            put("task", buildJsonObject {
                put("status", "succeeded")
                put("summary", "done")
            })
        }
        val state = TaskToolUI.taskState(context(textOutput(json.toString())))

        assertEquals(TaskRunDisplayStatus.Succeeded, state.status)
        assertFalse(state.resumable)
        assertFalse(state.pendingChildApproval)
    }

    // Legacy / fallback: the bare final-text output every pre-M5 transcript carries. The renderer
    // must treat it as a completed task whose summary IS the text, never crash on the missing
    // envelope.
    @Test
    fun `a bare final-text output falls back to a succeeded task with the text as summary`() {
        val state = TaskToolUI.taskState(context(textOutput("the subagent's final answer")))

        assertEquals(TaskRunDisplayStatus.Succeeded, state.status)
        assertEquals("the subagent's final answer", state.summary)
        assertFalse(state.resumable)
        assertFalse(state.pendingChildApproval)
    }

    // While the tool call is still generating (no output yet) the task is shown as running.
    @Test
    fun `an unexecuted tool call is shown as running with no summary`() {
        val state = TaskToolUI.taskState(context(output = emptyList(), loading = true))

        assertEquals(TaskRunDisplayStatus.Running, state.status)
        assertNull(state.summary)
    }

    // An unknown status string must not crash the chat UI: fall back to Running rather than throw.
    @Test
    fun `an unknown status string falls back to running`() {
        val json = buildJsonObject {
            put("task", buildJsonObject { put("status", "no_such_status") })
        }
        val state = TaskToolUI.taskState(context(textOutput(json.toString())))

        assertEquals(TaskRunDisplayStatus.Running, state.status)
    }
}
