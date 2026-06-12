package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.ai.task.buildTaskEnvelope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The reserved tool NAME the spawn ("task") tool occupies. A subagent must never be able to
 * spawn further subagents (recursion guard, depth bounded at 1), so this name is filtered out
 * of any tool pool handed to a subagent — see [filterToolsForSubagent], which takes this name as
 * its caller-supplied reserved name. This identity is app-side because the spawn [Tool] itself is
 * built only here; the neutral `:ai-runtime` recursion-guard names no concrete tool.
 *
 * Raw MCP tools are prefixed `mcp__` at the ChatService build site, so a malicious MCP tool
 * literally named `task` becomes `mcp__task` and cannot collide with this reserved name. Filtering
 * by the exact name is therefore a sound structural guard.
 */
const val SPAWN_TOOL_NAME: String = "task"

/**
 * The spawn ("task") [Tool] that lets the parent assistant delegate a self-contained sub-task to a
 * named, `spawnable` [Assistant] (issue #201; rewired onto [TaskCoordinator] in SPEC.md M4).
 *
 * The tool's wire surface is UNCHANGED — same reserved name [SPAWN_TOOL_NAME], same `subagent` /
 * `prompt` args, same `UIMessagePart` output — so existing callers and transcripts keep working.
 * What changed under it: the child now runs through [TaskCoordinator] instead of `SubagentRunner`,
 * so the run is a persisted, lifecycle-tracked, budget/concurrency-gated [me.rerere.ai.runtime.task.TaskState]
 * machine. The final answer still lands in the same `UIMessagePart.Tool` output (the parent's
 * tool result), while the coordinator emits live lifecycle events for the task renderer (M5).
 *
 * Built ONLY at the parent's per-generation tool buildList (`ChatService`). A subagent reaches the
 * engine via [TaskCoordinator] -> `generateText` directly, so its tool pool never goes through that
 * buildList and therefore never contains this tool — the recursion guard is structural (depth
 * bounded at 1, TASK_DEPTH_ONE), not a runtime flag. [SPAWN_TOOL_NAME] is additionally filtered by
 * [filterToolsForSubagent] as a belt-and-suspenders guard against any other source impersonating
 * the name.
 *
 * @param spawnableAssistants the assistants the user has marked `spawnable`; the tool advertises
 *   each by its `description` and resolves the `subagent` argument against this set by name.
 * @param parentModelId the spawning assistant's model, inherited by a sub that pins none
 *   ([resolveSubagentModel]).
 * @param buildSubagentTools builds the TARGET (sub) assistant's own tool pool from its allowlist
 *   (local + skills + MCP). Supplied by the caller (`ChatService`) so this factory stays free of
 *   Android `Context` / managers. The pool is filtered before it reaches the engine: the spawn tool
 *   is stripped by [TaskCoordinator.run] ([filterToolsForSubagent]) and `needsApproval=true` tools
 *   are dropped here, because the approval UI is unreachable mid-subagent (v1 security note).
 * @param progressLabel produces the parent-facing processingStatus string for a running subagent
 *   (kept as a lambda so this factory stays free of Android `Context`).
 * @param parentConversationId the conversation whose generation owns this spawn. It is threaded
 *   into [TaskCoordinator.run] so the persisted task row is associated with the REAL conversation
 *   instead of [TaskCoordinator.run]'s `Uuid.random()` default — per-conversation lookup (the board
 *   panel, retention's keep-newest-per-conversation, conversation-delete cleanup) all key on this
 *   column (review finding #2). It is REQUIRED, not defaulted: a default here would silently
 *   re-introduce the random-conversation orphan the fix removes.
 */
fun buildSpawnTool(
    spawnableAssistants: List<Assistant>,
    coordinator: TaskCoordinator,
    parentModelId: Uuid?,
    settings: Settings,
    buildSubagentTools: (sub: Assistant) -> List<Tool>,
    processingStatus: MutableStateFlow<String?>,
    progressLabel: (subName: String) -> String,
    parentConversationId: Uuid,
): Tool = Tool(
    name = SPAWN_TOOL_NAME,
    description = "Delegate a self-contained sub-task to a specialized subagent and return its result.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("subagent", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("The name of the subagent to run (see the system prompt for the available subagents and when to use each)."),
                    )
                })
                put("prompt", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("The full self-contained task for the subagent. It does not see this conversation; include all needed context."),
                    )
                })
            },
            required = listOf("subagent", "prompt"),
        )
    },
    systemPrompt = { _, _ -> advertiseSpawnableAssistants(spawnableAssistants) },
    // Approval UI is unreachable mid-subagent (v1 security note in the design): a subagent runs
    // only needsApproval=false tools. The spawn tool itself is therefore auto.
    needsApproval = false,
    execute = { args ->
        val subName = args.subagentArg()
        val prompt = args.promptArg()
        val sub = spawnableAssistants.firstOrNull { it.name == subName }
            ?: error("No spawnable subagent named \"$subName\". Available: ${spawnableAssistants.joinToString { it.name }}")

        // The sub's own tool pool, minus tools whose approval UI is unreachable mid-subagent
        // (v1 security note: a subagent runs only needsApproval=false tools). The spawn tool is
        // additionally stripped inside TaskCoordinator.run (recursion guard, TASK_DEPTH_ONE).
        val subTools = buildSubagentTools(sub).filterNot { it.needsApproval }

        // Set-then-clear discipline (mirrors OcrTransformer / KnowledgeContextTransformer):
        // restore the prior status on EVERY terminal path so a stale "Running <sub>" label can't
        // leak into the parent's loading UI — including the error() throw on an unknown subagent
        // / unresolvable model bubbling out of runner.run.
        val prevStatus = processingStatus.value
        processingStatus.value = progressLabel(sub.name)
        val result = try {
            coordinator.run(
                sub = sub,
                prompt = prompt,
                parentModelId = parentModelId,
                settings = settings,
                tools = subTools,
                processingStatus = processingStatus,
                parentConversationId = parentConversationId,
                // parentToolCallId is intentionally NOT passed: the spawn tool call id is not
                // reachable inside Tool.execute (its signature is `suspend (JsonElement) -> …`,
                // engine-wide). Plumbing it would require an ABI change to the shared Tool type and
                // the spawn-path tool assembly this spec marks Ask-first — tracked as a follow-up
                // gap (review finding #2, parentToolCallId half). Until then per-parent concurrency
                // grouping falls back to the global cap, which is still enforced.
            )
        } finally {
            processingStatus.value = prevStatus
        }
        // Emit the structured {task:{...}} envelope (review finding #1) so the live renderer shows
        // the TERMINAL status, budget counters, and interrupted/budget-exhausted identity instead
        // of always falling back to a bare-text "Done". The envelope is JSON in a Text part — the
        // existing `UIMessagePart.Tool` output shape, no new part subtype (v1 prohibition).
        listOf(UIMessagePart.Text(buildTaskEnvelope(result).toString()))
    },
)

/**
 * The system-prompt block advertising every spawnable assistant by its `description`. Assistants
 * with a blank description are skipped (nothing useful to tell the model about when to call them).
 * Returns "" when nothing is advertisable, so the tool prompt adds no noise.
 */
internal fun advertiseSpawnableAssistants(spawnableAssistants: List<Assistant>): String {
    val lines = spawnableAssistants
        .filter { it.name.isNotBlank() && it.description.isNotBlank() }
        .map { "- ${it.name}: ${it.description}" }
    if (lines.isEmpty()) return ""
    return buildString {
        append("Available subagents you can run via the `$SPAWN_TOOL_NAME` tool ")
        append("(pass the subagent's name as `subagent` and a self-contained task as `prompt`):")
        append("\n")
        append(lines.joinToString("\n"))
    }
}

private fun JsonElement.subagentArg(): String =
    jsonObject["subagent"]?.jsonPrimitive?.content?.trim().orEmpty()

private fun JsonElement.promptArg(): String =
    jsonObject["prompt"]?.jsonPrimitive?.content.orEmpty()
