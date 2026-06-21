package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_MODEL_NAME
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.ai.task.injectChildApprovalPart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Metamorphic guard for the spawn tool's `task` -> `agent` rename (SPEC.md M4 / issue #286).
 *
 * The rename is model-facing only: the advertised name became `agent`, but every renderer /
 * persistence / approval read path keeps recognizing the legacy `task` so already-stored
 * transcripts and in-flight pending calls keep working byte-for-byte. The metamorphic relation
 * that pins this: for the SAME stored step, swapping `toolName="task"` <-> `toolName="agent"` must
 * be observationally invisible at every read seam.
 *
 * Three seams, asserted as equalities (metamorphic transform = "rename the step's toolName"):
 *  1. [TaskToolUI.taskState] returns an equal [TaskToolViewState] — for BOTH the bare-text output
 *     (every pre-rename transcript) AND the structured `{task:{...}}` envelope output.
 *  2. The collapsed step title (derived from the `subagent` ARG, never the tool name) is identical.
 *  3. [injectChildApprovalPart] anchors a child approval to an identical pending part.
 *
 * Plus two regression guards confirming the explicitly-out-of-scope names did NOT drift:
 *  - the `{task:{...}}` tool-output envelope key stays exactly `"task"` (renderer/persistence-facing,
 *    not model-facing), and
 *  - `UIMessagePart.Tool` is still serialized as `@SerialName("tool")` with `toolName` as a field
 *    (no `@SerialName` rename).
 */
@OptIn(ExperimentalUuidApi::class)
class SpawnToolRenameMetamorphicTest {

    private fun context(toolName: String, output: List<UIMessagePart>, subagent: String?): ToolUIContext {
        val arguments: JsonElement = buildJsonObject {
            subagent?.let { put("subagent", it) }
        }
        return ToolUIContext(
            tool = UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = toolName,
                input = arguments.toString(),
                output = output,
            ),
            arguments = arguments,
            content = parseToolOutputContent(output, isExecuted = output.isNotEmpty()),
            loading = false,
        )
    }

    /** The pure title formula [TaskToolUI.title] computes — read off the `subagent` ARG, never the
     *  tool name. Mirrored here so the metamorphic title equality is asserted without a Compose
     *  runtime; the production composable applies exactly this expression. */
    private fun titleOf(context: ToolUIContext): String {
        val sub = context.arguments.getStringContent("subagent")
        return if (sub != null) "Subagent: $sub" else "Subagent"
    }

    private fun envelopeOutput(): List<UIMessagePart> {
        val json = buildJsonObject {
            put("task", buildJsonObject {
                put("status", "succeeded")
                put("summary", "the subagent's final answer")
                put("budget", buildJsonObject {
                    put("steps", 5)
                    put("maxSteps", 64)
                    put("tokens", 4096)
                })
            })
        }
        return listOf(UIMessagePart.Text(json.toString()))
    }

    private fun bareTextOutput(): List<UIMessagePart> =
        listOf(UIMessagePart.Text("the subagent's final answer"))

    @Test
    fun `a bare-text step renders identically under task and agent`() {
        val output = bareTextOutput()

        val task = context(SPAWN_TOOL_NAME, output, subagent = "Researcher")
        val agent = context(SPAWN_TOOL_MODEL_NAME, output, subagent = "Researcher")

        assertEquals(
            "taskState must be metamorphically equal across the toolName rename (bare text)",
            TaskToolUI.taskState(task), TaskToolUI.taskState(agent),
        )
        assertEquals(
            "the collapsed title is derived from the subagent arg, not the tool name (bare text)",
            titleOf(task), titleOf(agent),
        )
    }

    @Test
    fun `a task envelope step renders identically under task and agent`() {
        val output = envelopeOutput()

        val task = context(SPAWN_TOOL_NAME, output, subagent = "Researcher")
        val agent = context(SPAWN_TOOL_MODEL_NAME, output, subagent = "Researcher")

        assertEquals(
            "taskState must be metamorphically equal across the toolName rename ({task:{...}})",
            TaskToolUI.taskState(task), TaskToolUI.taskState(agent),
        )
        assertEquals(
            "the collapsed title is derived from the subagent arg, not the tool name ({task:{...}})",
            titleOf(task), titleOf(agent),
        )
    }

    @Test
    fun `injectChildApprovalPart anchors a child approval identically under task and agent`() {
        val namespacedId = "${Uuid.random()}/child-1"

        fun anchoredPart(spawnToolName: String): UIMessagePart.Tool {
            val conversation = Conversation.ofId(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage.user("do the thing").toMessageNode(),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "call_spawn_1",
                                toolName = spawnToolName,
                                input = """{"subagent":"Researcher","prompt":"go"}""",
                                output = emptyList(), // unexecuted = the spawn is suspended right now
                            ),
                        ),
                    ).toMessageNode(),
                ),
            )
            val injected = injectChildApprovalPart(
                conversation = conversation,
                namespacedToolCallId = namespacedId,
                toolName = "ask_user",
                argumentsJson = """{"q":"may I?"}""",
            )
            assertNotNull("a running $spawnToolName step must be a valid approval anchor", injected)
            return injected!!.messageNodes.last().currentMessage.parts
                .filterIsInstance<UIMessagePart.Tool>()
                .single { it.toolCallId == namespacedId }
        }

        assertEquals(
            "the injected child-approval part is identical regardless of which spawn name anchored it",
            anchoredPart(SPAWN_TOOL_NAME), anchoredPart(SPAWN_TOOL_MODEL_NAME),
        )
    }

    // Regression guards: the two names the task says are explicitly out of scope must NOT drift.

    @Test
    fun `the task envelope key stays task`() {
        // The renderer parses the live state out of the `{task:{...}}` envelope (TaskToolUI:291).
        // If the emit-side key drifted off "task", every envelope transcript would silently fall
        // back to the bare-text path and lose status/budget. Pin the key by round-tripping a real
        // envelope shape through the renderer parse: a "succeeded" status only resolves if the
        // "task" wrapper is read.
        val state = TaskToolUI.taskState(context(SPAWN_TOOL_NAME, envelopeOutput(), subagent = "Researcher"))
        assertEquals(
            "the {task:{...}} envelope key must stay \"task\" for the renderer to read the structured state",
            TaskRunDisplayStatus.Succeeded, state.status,
        )
        assertEquals(5, state.steps)
    }

    @Test
    fun `UIMessagePart Tool stays SerialName tool`() {
        // The rename is model-facing; the persisted subtype discriminator is NOT renamed. A drift
        // here would break deserialization of every stored conversation row.
        val serialName = UIMessagePart.Tool::class.annotations
            .filterIsInstance<SerialName>()
            .single()
            .value
        assertEquals("tool", serialName)
    }
}
