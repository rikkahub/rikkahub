package me.rerere.rikkahub.data.ai.schedule

import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.rikkahub.data.ai.task.TaskCoordinator
import me.rerere.rikkahub.data.ai.task.deniedChildToolResult
import me.rerere.rikkahub.data.ai.task.gateSubagentTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ScheduleClaim
import kotlin.uuid.Uuid

/**
 * Turns a winning [ScheduleClaim] into a real task run by REUSING [TaskCoordinator.run] (SPEC.md M5
 * / task T8). A scheduled fire is exactly "spawn a sub-task against the target assistant", driven
 * from a WorkManager worker instead of a parent's `task` tool call — so the run itself must be the
 * SAME lifecycle-tracked, budget/concurrency-gated [me.rerere.ai.runtime.task.TaskState] machine a
 * live spawn drives. This runner therefore invents NO second runner and NO new concurrency
 * primitive: it calls [TaskCoordinator.run], which already queues every scheduled run behind live
 * spawns under its own process-wide global [kotlinx.coroutines.sync.Semaphore]. [TaskCoordinator.run]'s
 * signature is unchanged.
 *
 * SoC / DIP: this runner names no Room, no settings store, no WorkManager. It depends on three
 * narrow injected seams — [resolveAssistant] (the target lookup), [currentSettings] (the live
 * Settings snapshot), and [buildChildTools] (the target's own tool pool) — bound at the composition
 * root, mirroring how [TaskCoordinator] and [me.rerere.rikkahub.data.repository.TaskScheduleRepository]
 * take injected lookups. That keeps it JVM-unit-testable with fakes (no Context, no Android).
 *
 * **Approval surface.** A scheduled run has NO live parent approval surface (no chat turn is
 * waiting). Per the v1 constraint it reuses the existing child-approval path: every child tool is
 * rewritten through [gateSubagentTools] with an AUTO-DENY gate ([AutoDenyApprovalGate]) — the
 * empty-allowlist behavior, where every `needsApproval=true` child tool auto-denies without ever
 * reaching a (nonexistent) approval surface, and the real tool never runs. This is the same gate
 * shape the live spawn path uses with an empty allowlist; no new "scheduled approval" surface is
 * invented (spec assumption 6).
 *
 * @param coordinator the shared task coordinator — its existing global semaphore caps in-flight
 *   children process-wide, so a scheduled run queues behind live spawns under the same cap.
 * @param resolveAssistant resolves the schedule's target assistant id to a live [Assistant], or
 *   null if it no longer exists. A missing target is a no-op fire (returns null), not a crash.
 * @param currentSettings the live [Settings] snapshot the run executes under (model resolution,
 *   providers). Read at fire time so a stale enqueued request always runs against current config.
 * @param buildChildTools builds the target assistant's own tool pool (local + skills + MCP). Bound
 *   at the composition root so this runner stays free of Android managers, exactly as `ChatService`
 *   supplies the spawn tool's `buildSubagentTools`.
 */
class ScheduledTaskRunner(
    private val coordinator: TaskCoordinator,
    private val resolveAssistant: suspend (Uuid) -> Assistant?,
    private val currentSettings: suspend () -> Settings,
    private val buildChildTools: suspend (Assistant) -> List<Tool>,
) {
    /**
     * Fire [claim] against its target assistant and return the run's id (the claim's [ScheduleClaim.runId],
     * which is the `taskId` the coordinator persists the run under — the same id the worker finishes
     * the schedule against). Returns null if the target no longer exists (a no-op fire). The run uses
     * the claim's run id as its task id so the persisted task row, the gate, and the schedule's
     * `running_task_run_id` all agree on one identity.
     *
     * @param parentConversationId the schedule's bound parent conversation (the schedule's mandatory
     *   parent). Threaded into [TaskCoordinator.run] so the persisted task row keys on the real
     *   conversation — never [TaskCoordinator.run]'s `Uuid.random()` default.
     */
    suspend fun run(claim: ScheduleClaim, parentConversationId: Uuid): Uuid? {
        val target = resolveAssistant(claim.snapshot.targetAssistantId) ?: return null
        val settings = currentSettings()

        // No live approval surface: every approval-gated child tool auto-denies (empty-allowlist
        // path), the real tool never runs. The gate keys on the run's task id (= the claim run id),
        // so the rewritten pool and the persisted run share one identity.
        val childTools = gateSubagentTools(
            tools = buildChildTools(target),
            taskId = claim.runId,
            gate = AutoDenyApprovalGate,
        )

        coordinator.run(
            sub = target,
            prompt = claim.snapshot.prompt,
            // A scheduled run has no parent model to inherit; the coordinator resolves the model from
            // the target's own pin or the settings default.
            parentModelId = null,
            settings = settings,
            tools = childTools,
            parentConversationId = parentConversationId,
            taskId = claim.runId,
        )
        return claim.runId
    }
}

/**
 * The auto-deny child-approval gate for scheduled runs: every forwarded request is DENIED, never
 * reaching any parent surface (there is none mid-schedule). This is the empty-allowlist behavior the
 * spec names — it reuses the existing [gateSubagentTools] path rather than inventing a new scheduled
 * approval surface (spec assumption 6). The reason mirrors the live router's auto-deny string so the
 * child sees an unambiguous "did not run; do not retry".
 */
internal object AutoDenyApprovalGate : TaskApprovalGate {
    override suspend fun await(taskId: Uuid, request: TaskApprovalRequest): TaskApprovalDecision =
        TaskApprovalDecision.Denied(
            deniedChildToolResult(request.toolName, reason = "scheduled runs have no approval surface")
        )
}
