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
import me.rerere.ai.runtime.subagent.SPAWN_TOOL_NAME
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The spawn ("task") [Tool] that lets the parent assistant delegate a self-contained sub-task to a
 * named, `spawnable` [Assistant] and get back just its final text (issue #201, slice 4).
 *
 * Built ONLY at the parent's per-generation tool buildList (`ChatService`). A subagent reaches the
 * engine via [SubagentRunner] -> `generateText` directly, so its tool pool never goes through that
 * buildList and therefore never contains this tool — the recursion guard is structural (depth
 * bounded at 1), not a runtime flag. [SPAWN_TOOL_NAME] is additionally filtered by
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
 *   is stripped by [SubagentRunner.run] ([filterToolsForSubagent]) and `needsApproval=true` tools
 *   are dropped here, because the approval UI is unreachable mid-subagent (v1 security note).
 * @param progressLabel produces the parent-facing processingStatus string for a running subagent
 *   (kept as a lambda so this factory stays free of Android `Context`).
 */
fun buildSpawnTool(
    spawnableAssistants: List<Assistant>,
    runner: SubagentRunner,
    parentModelId: Uuid?,
    settings: Settings,
    buildSubagentTools: (sub: Assistant) -> List<Tool>,
    processingStatus: MutableStateFlow<String?>,
    progressLabel: (subName: String) -> String,
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
        // additionally stripped inside SubagentRunner.run (recursion guard).
        val subTools = buildSubagentTools(sub).filterNot { it.needsApproval }

        // Set-then-clear discipline (mirrors OcrTransformer / KnowledgeContextTransformer):
        // restore the prior status on EVERY terminal path so a stale "Running <sub>" label can't
        // leak into the parent's loading UI — including the error() throw on an unknown subagent
        // / unresolvable model bubbling out of runner.run.
        val prevStatus = processingStatus.value
        processingStatus.value = progressLabel(sub.name)
        val result = try {
            runner.run(
                sub = sub,
                prompt = prompt,
                parentModelId = parentModelId,
                settings = settings,
                tools = subTools,
                processingStatus = processingStatus,
            )
        } finally {
            processingStatus.value = prevStatus
        }
        listOf(UIMessagePart.Text(result))
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
