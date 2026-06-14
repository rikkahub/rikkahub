package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.subagent.filterToolsForSubagent
import me.rerere.ai.runtime.task.TaskState
import me.rerere.rikkahub.data.ai.task.ExecutionHandle
import me.rerere.rikkahub.data.ai.task.ExecutionHandleRegistry
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.ai.task.buildTaskEnvelope
import me.rerere.rikkahub.data.ai.task.gateSubagentTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The advertised (model-facing) NAME of the spawn tool. The model sees ONLY this name and the
 * system prompt refers to it. Renamed `task` -> `agent` (issue #286) so it no longer collides with
 * the work-board `task_*` family the model also sees in the same turn pool — a bare `task` was
 * ambiguously readable as the board's "create" verb. This is the name [buildSpawnTool] advertises.
 */
const val SPAWN_TOOL_MODEL_NAME: String = "agent"

/**
 * The legacy execution / UI / approval alias for the spawn tool — kept, NOT advertised for fresh
 * turns. Pre-rename transcripts and in-flight pending calls carry this name in their persisted
 * `UIMessagePart.Tool.toolName`; keeping it resolvable on every read path (pool resolution, renderer
 * registry, child-approval anchor) means stored conversations keep working byte-for-byte without
 * rewriting any stored row. A subagent must never be able to spawn further subagents (recursion
 * guard, depth bounded at 1), so BOTH this name and [SPAWN_TOOL_MODEL_NAME] are filtered out of any
 * tool pool handed to a subagent — see [filterToolsForSubagent], which takes a name as its
 * caller-supplied reserved name. This identity is app-side because the spawn [Tool] itself is built
 * only here; the neutral `:ai-runtime` recursion-guard names no concrete tool.
 *
 * Raw MCP tools are prefixed `mcp__` at the ChatService build site, so a malicious MCP tool
 * literally named `task` becomes `mcp__task` and cannot collide with this reserved name. Filtering
 * by the exact name is therefore a sound structural guard.
 */
const val SPAWN_TOOL_NAME: String = "task"

/**
 * Strip BOTH spawn-tool names from a subagent's tool pool — the advertised [SPAWN_TOOL_MODEL_NAME]
 * (`agent`) AND the legacy execution alias [SPAWN_TOOL_NAME] (`task`). A subagent must never be
 * able to spawn further subagents (recursion guard, depth bounded at 1), and either name reaching a
 * child pool would defeat that — so the depth-1 guard must remove both.
 *
 * The two-name policy lives in `:app` (where both names are defined), not in the neutral
 * `:ai-runtime` primitive: [filterToolsForSubagent] takes ONE caller-supplied name and stays
 * name-agnostic. This helper chains it for both names so the two-call pattern is not duplicated at
 * the strip sites (catalog subagent branch, [TaskCoordinator.run], [TaskCoordinator.resume]).
 */
fun stripSpawnTools(tools: List<Tool>): List<Tool> =
    filterToolsForSubagent(filterToolsForSubagent(tools, SPAWN_TOOL_MODEL_NAME), SPAWN_TOOL_NAME)

/**
 * The spawn ("agent", legacy alias "task") [Tool] that lets the parent assistant delegate a
 * self-contained sub-task to a named, `spawnable` [Assistant] (issue #201; rewired onto
 * [TaskCoordinator] in SPEC.md M4; model-facing name decoupled to `agent` in #286).
 *
 * The tool is advertised to the model under [SPAWN_TOOL_MODEL_NAME] (`agent`); [SPAWN_TOOL_NAME]
 * (`task`) survives as the legacy resolution / UI / approval alias. The rest of the wire surface is
 * UNCHANGED — same `subagent` / `prompt` args, same `UIMessagePart` output — so existing callers and
 * transcripts keep working.
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
 * @param registry the live execution-handle home (SPEC.md M4/M6). Every spawn registers ONE
 *   handle whose [kotlinx.coroutines.Job] is a structural child of the spawning coroutine's job,
 *   hands it to [buildSubagentTools] so board claims are owned by the HANDLE id, and tears it
 *   down on every terminal path — releasing the dead handle's remaining board claims through
 *   [releaseOrphanedClaims] (orphan recovery, decision #5) before unregistering.
 * @param buildSubagentTools builds the TARGET (sub) assistant's own tool pool from its allowlist
 *   (local + skills + MCP), given the run's live execution handle (so the board tools bind their
 *   claim owner to it). Supplied by the caller (`ChatService`) so this factory stays free of
 *   Android `Context` / managers. The pool is rewritten before it reaches the engine: the spawn
 *   tool is stripped by [TaskCoordinator.run] ([filterToolsForSubagent]), and `needsApproval=true`
 *   tools are GATED through [approvalGateFor] (Gap A) — the child runtime never gates anything
 *   itself; the gate forwards allowlisted tools to the parent's approval surface and auto-denies
 *   the rest (maintainer decision #2 — replaces the v1 strip-all behavior).
 * @param approvalGateFor the child-approval gate for a given sub (production:
 *   `TaskApprovalRouter` over the sub's explicit `subagentApprovalAllowlist`, default EMPTY =
 *   forward nothing). Per-sub because the allowlist is per-assistant.
 * @param releaseOrphanedClaims releases EVERY board claim still owned by the given handle id —
 *   bound to `TaskBoardRepository.releaseClaimsOf` at the composition root. Invoked on every
 *   terminal path (success, failure, cancellation) because the handle is dead either way; a claim
 *   the child completed keeps its owner for display and is untouched by release.
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
    registry: ExecutionHandleRegistry,
    buildSubagentTools: (sub: Assistant, handle: ExecutionHandle) -> List<Tool>,
    releaseOrphanedClaims: suspend (handleId: String) -> Unit,
    approvalGateFor: (sub: Assistant) -> TaskApprovalGate,
    processingStatus: MutableStateFlow<String?>,
    progressLabel: (subName: String) -> String,
    parentConversationId: Uuid,
): Tool = Tool(
    name = SPAWN_TOOL_MODEL_NAME,
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

        // One live execution handle per spawn (SPEC.md M4): its Job is a structural child of the
        // spawning coroutine's job, so the parent's cancel cascades without registry bookkeeping.
        // Registered only AFTER the sub resolved — an unknown-subagent error has no handle to leak.
        val parentJob = requireNotNull(currentCoroutineContext()[Job]) {
            "spawn tool must run inside a coroutine with a Job"
        }
        val handle = registry.register(
            conversationId = parentConversationId,
            assistantId = sub.id,
            parentJob = parentJob,
        )

        // The run's identity is minted HERE so the approval gate and the persisted task row agree
        // on it — the gate's events/summaries land on the same row coordinator.run creates.
        val taskId = Uuid.random()

        // Set-then-clear discipline (mirrors OcrTransformer / KnowledgeContextTransformer):
        // restore the prior status on EVERY terminal path so a stale "Running <sub>" label can't
        // leak into the parent's loading UI. Captured before the guarded block; restoring an
        // unset status is a no-op.
        val prevStatus = processingStatus.value
        // The lifecycle closure starts IMMEDIATELY after the handle exists (review mustFix #3):
        // tool assembly below can throw, and an exit between register and the old try meant the
        // handle — whose Job is a child of the generation job — was never unregistered, pinning
        // the parent's job in `completing` forever.
        // A claim-release failure in the finally must never REPLACE the primary outcome (a throw
        // out of a finally supersedes the in-flight exception — on the cancellation path that
        // would swallow the CancellationException and break cooperative cancellation). It is
        // captured here and rethrown ONLY when the run itself completed normally; on an
        // exceptional/cancelled exit the primary wins and the lease bounds the claims left held.
        var releaseFailure: Throwable? = null
        val result = try {
            // The sub's own tool pool — its board claims owned by the handle — with approval-gated
            // tools rewired through the parent's approval gate (Gap A): the child runtime sees only
            // needsApproval=false tools (its approval UI is unreachable mid-subagent), and the gate
            // forwards allowlisted calls to the parent while auto-denying the rest (decision #2).
            // The spawn tool is additionally stripped inside TaskCoordinator.run (recursion guard,
            // TASK_DEPTH_ONE).
            val subTools = gateSubagentTools(
                tools = buildSubagentTools(sub, handle),
                taskId = taskId,
                gate = approvalGateFor(sub),
            )

            processingStatus.value = progressLabel(sub.name)
            registry.markRunning(handle.id)
            coordinator.run(
                sub = sub,
                prompt = prompt,
                parentModelId = parentModelId,
                settings = settings,
                tools = subTools,
                processingStatus = processingStatus,
                parentConversationId = parentConversationId,
                taskId = taskId,
                // parentToolCallId is intentionally NOT passed: the spawn tool call id is not
                // reachable inside Tool.execute (its signature is `suspend (JsonElement) -> …`,
                // engine-wide). Plumbing it would require an ABI change to the shared Tool type and
                // the spawn-path tool assembly this spec marks Ask-first — tracked as a follow-up
                // gap (review finding #2, parentToolCallId half). Until then per-parent concurrency
                // grouping falls back to the global cap, which is still enforced.
            ).also { run ->
                // Mirror the run's terminal into the handle state machine; the coordinator already
                // absorbs child failures into the result (it rethrows only cancellation).
                when (val terminal = run.state) {
                    is TaskState.Failed -> registry.markFailed(handle.id, terminal.error)
                    else -> registry.markCompleted(handle.id, run.text)
                }
            }
        } catch (cancellation: CancellationException) {
            registry.stop(handle.id)
            throw cancellation
        } catch (error: Throwable) {
            registry.markFailed(handle.id, error.message ?: error::class.simpleName.orEmpty())
            throw error
        } finally {
            processingStatus.value = prevStatus
            // The handle is dead on EVERY exit: release every board claim it still holds (orphan
            // recovery, decision #5 — the lease is only the backstop for paths recovery cannot
            // reach, i.e. process death), then drop it. NonCancellable because the release
            // suspends and must still run on the cancellation path. unregister is UNCONDITIONAL
            // (review mustFix #3): a throwing release must still complete the handle Job, or the
            // very job-pin this teardown exists to prevent comes back; the lease then bounds the
            // claims the failed release left behind.
            try {
                withContext(NonCancellable) { releaseOrphanedClaims(handle.id) }
            } catch (error: Throwable) {
                releaseFailure = error
            } finally {
                registry.unregister(handle.id)
            }
        }
        // Reached only on the normal-completion path: a release failure must not pass silently.
        releaseFailure?.let { throw it }
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
        append("Available subagents you can run via the `$SPAWN_TOOL_MODEL_NAME` tool ")
        append("(pass the subagent's name as `subagent` and a self-contained task as `prompt`):")
        append("\n")
        append(lines.joinToString("\n"))
    }
}

private fun JsonElement.subagentArg(): String =
    jsonObject["subagent"]?.jsonPrimitive?.content?.trim().orEmpty()

private fun JsonElement.promptArg(): String =
    jsonObject["prompt"]?.jsonPrimitive?.content.orEmpty()
