package me.rerere.rikkahub.workflow.execution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findAssistantById
import me.rerere.rikkahub.workflow.condition.ConditionEvaluator
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.trigger.TriggerFireCallback
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 12 — workflow execution engine. The single entry point for any workflow fire.
 *
 * Lifecycle of a fire (matches `headless = true` semantics from cron jobs):
 *  1. Lookup workflow + verify enabled.
 *  2. Cooldown check — `lastRunAtMs + cooldownSeconds` against now.
 *  3. Daily-cap check — counted fires (SUCCESS+FAILED) for today's local date.
 *  4. Build [WorkflowContext] — lazy on location for sunset/sunrise conditions.
 *  5. Evaluate conditions; AND-combined.
 *  6. Resolve assistant + tool list. Workflows are app-global, but actions still need a
 *     tool surface to execute against — we use the first assistant with the Workflows
 *     toggle on (the toggle gates *authoring*; runtime fallback is reasonable).
 *  7. Execute action sequence via [DirectModeActionRunner] — every action HARDLINE-checked.
 *  8. Persist run row, projected last-run state, daily counter, trim history.
 *
 * Concurrency: per-workflow mutex so two near-simultaneous fires (e.g. WiFi flicker) can't
 * race on the daily counter. Cross-workflow execution stays parallel.
 *
 * Approval semantics: HARDLINE applies in workflow context. Tool factories that set
 * `needsApproval = true` would normally pop a prompt — workflows are headless and the
 * pre-authorisation is the workflow_create approval the user already granted. So the
 * action runner just calls the tool's [Tool.execute] directly. This matches scheduled-jobs
 * direct-mode behavior.
 *
 * The `Workflows` per-assistant toggle gates the seven `workflow_*` LLM tools, NOT the
 * trigger pipeline. A workflow that's been authored stays armed regardless of which
 * assistant the user is currently chatting with. Trigger dispatch is gated by the
 * workflow's own `enabled` flag.
 */
class WorkflowEngine(
    private val repository: WorkflowRepository,
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
    private val contextProvider: ContextProvider,
    private val actionRunner: WorkflowActionRunner,
) {

    private val perWorkflowLocks = mutableMapOf<String, Mutex>()
    private val locksMutex = Mutex()

    private suspend fun lockFor(id: String): Mutex = locksMutex.withLock {
        perWorkflowLocks.getOrPut(id) { Mutex() }
    }

    /**
     * Trigger callback target. The registry hands every fire here. [matchSpec] is the
     * variant that fired — used for diagnostics; the workflow's own [WorkflowDefinition.trigger]
     * is the source of truth for its semantics.
     */
    val triggerCallback = TriggerFireCallback { workflowId, _ -> fire(workflowId) }

    /**
     * Fire a workflow. Resolves cooldown / daily cap / conditions, then runs the action
     * sequence. Returns the resulting status — useful for `workflow_run` synchronous tool
     * call, ignored by the trigger callback path.
     */
    suspend fun fire(workflowId: String): FireOutcome = withContext(Dispatchers.IO) {
        val lock = lockFor(workflowId)
        lock.withLock { fireLocked(workflowId) }
    }

    private suspend fun fireLocked(workflowId: String): FireOutcome {
        val firedAtMs = System.currentTimeMillis()
        val started = System.nanoTime()
        val loaded = repository.getById(workflowId)
            ?: return FireOutcome(WorkflowRunStatus.FAILED, "workflow_not_found", "")
        val def = loaded.definition
        val entity = loaded.entity

        if (!entity.enabled) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DISABLED, null, "")
        }

        // Cooldown gate
        if (def.cooldownSeconds > 0 && entity.lastRunAtMs != null) {
            val nextAllowed = entity.lastRunAtMs + def.cooldownSeconds * 1000L
            if (firedAtMs < nextAllowed) {
                return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_COOLDOWN, null, "")
            }
        }

        // Daily-cap gate
        if (def.maxRunsPerDay != null) {
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            val countedToday = if (entity.runsTodayDate == today) entity.runsTodayCount else 0
            if (countedToday >= def.maxRunsPerDay) {
                return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DAILY_CAP, null, "")
            }
        }

        // Conditions
        if (def.conditions.isNotEmpty()) {
            val ctx = contextProvider.snapshot(needsLocation = ConditionEvaluator.needsLocation(def.conditions))
            val cr = ConditionEvaluator.evaluateAll(def.conditions, ctx)
            if (cr is ConditionEvaluator.Result.FailedAt) {
                return persistAndReturn(
                    workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_CONDITIONS,
                    "condition[${cr.index}] failed: ${cr.reason}", "",
                )
            }
        }

        // Resolve assistant + tools — pick the first assistant with the Workflows toggle on.
        // No fallback to a different assistant: if no assistant has the toggle, the workflow's
        // action runner would resolve against an UNRELATED tool surface and silently behave
        // differently from what the user authored. Mark the run FAILED with a stable reason
        // so the user can re-enable Workflows on an assistant.
        val settings = settingsStore.settingsFlow.first()
        val authoringAssistant = settings.assistants.firstOrNull { asst ->
            asst.localTools.any { it is me.rerere.rikkahub.data.ai.tools.LocalToolOption.Workflows }
        }
        if (authoringAssistant == null) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                "no_workflows_assistant", "")
        }
        val tools = localTools.getTools(authoringAssistant.localTools)

        // Execute the action sequence. ActionRunner enforces per-action timeout + HARDLINE.
        val result = actionRunner.run(def.actions, tools)
        val status = if (result.success) WorkflowRunStatus.SUCCESS else WorkflowRunStatus.FAILED
        return persistAndReturn(workflowId, firedAtMs, started, status, result.error, result.summary)
    }

    private suspend fun persistAndReturn(
        workflowId: String,
        firedAtMs: Long,
        startedNanos: Long,
        status: WorkflowRunStatus,
        error: String?,
        summary: String,
    ): FireOutcome {
        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000L
        runCatching {
            repository.recordFire(
                workflowId = workflowId,
                firedAtMs = firedAtMs,
                status = status,
                durationMs = durationMs,
                errorMessage = error,
            )
        }.onFailure { Log.w(TAG, "recordFire failed for $workflowId", it) }
        return FireOutcome(status, error, summary)
    }

    companion object { private const val TAG = "WorkflowEngine" }

    data class FireOutcome(
        val status: WorkflowRunStatus,
        val error: String?,
        val summary: String,
    )
}

/**
 * Sequential action runner — wraps [me.rerere.rikkahub.service.DirectModeActionRunner]'s
 * core logic but on the workflow side, since direct-mode's own runner takes a slightly
 * different action shape. Same HARDLINE-then-execute semantics.
 *
 * Per-action timeout is the action's [WorkflowAction.timeoutSeconds] field; default 60s.
 */
class WorkflowActionRunner {

    data class RunResult(val success: Boolean, val error: String?, val summary: String)

    suspend fun run(actions: List<WorkflowAction>, availableTools: List<Tool>): RunResult {
        val outputs = mutableListOf<String>()
        for ((idx, action) in actions.withIndex()) {
            val argsJson = action.args.toString()
            val hardlineReason = HardlineCommandGuard.checkTool(action.tool, argsJson)
            if (hardlineReason != null) {
                logSafe("workflow hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
                return RunResult(success = false,
                    error = "action $idx: hardline:$hardlineReason",
                    summary = outputs.joinToString("\n"))
            }
            val tool = availableTools.find { it.name == action.tool }
                ?: return RunResult(false, "action $idx: unknown_tool:${action.tool}", outputs.joinToString("\n"))
            val out = try {
                withTimeoutOrNull(action.timeoutSeconds * 1000L) { tool.execute(action.args) }
            } catch (t: Throwable) {
                logSafe("workflow action $idx tool=${action.tool} threw: ${t.message}")
                return RunResult(false,
                    "action $idx: ${t::class.simpleName}: ${t.message.orEmpty()}".take(500),
                    outputs.joinToString("\n"))
            }
            if (out == null) {
                return RunResult(false,
                    "action $idx: ${action.tool} exceeded ${action.timeoutSeconds}s",
                    outputs.joinToString("\n"))
            }
            // Surface the first ~200 chars of the tool's text output for the run history.
            val text = out.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            outputs += "[$idx] ${action.tool}: ${text.take(200)}"
        }
        return RunResult(true, null, outputs.joinToString("\n").take(2000))
    }

    /**
     * Wrap [Log.w] in a guard so JVM unit tests (where android.util.Log is unmocked)
     * don't crash before the runner can return its actual result.
     */
    private fun logSafe(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    companion object { private const val TAG = "WorkflowActionRunner" }
}
