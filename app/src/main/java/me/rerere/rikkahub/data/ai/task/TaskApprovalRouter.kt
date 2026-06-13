package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskToolPolicy
import kotlin.uuid.Uuid

/**
 * The child-approval router (SPEC.md M4 / T10, maintainer decision #2). It is the single decision
 * point between a spawned child's approval-gated tool call and the parent's approval surface, and
 * it enforces ONE rule: an EXPLICIT allowlist, never a heuristic.
 *
 *  - A child tool whose name is on the running agent type's [TaskToolPolicy] allowlist is
 *    FORWARDED to the parent — its pending approval becomes parent-visible, namespaced
 *    `taskId/childToolCallId`, and resolved through the existing `handleToolApproval` path. The
 *    parent's decision (approve OR deny) travels back to the child as the tool result.
 *  - Every other approval-gated child tool AUTO-DENIES without ever reaching the parent surface,
 *    and the denial reason is recorded in the task summary (decision #2) so the parent can see
 *    why a child tool was refused.
 *  - The conservative default is an EMPTY allowlist: forward nothing — identical to today's
 *    strip-all-approval-tools subagent behavior.
 *
 * SoC / DIP: the router names no concrete tool and contains no UI/transport code. It depends on
 * two narrow ports — [ParentApprovalSurface] (the parent's approval seam, bound to `ChatService`
 * at the composition root) and [TaskRunStore] (to append the auto-deny reason) — plus a
 * per-task [TaskToolPolicy] resolver. This keeps it JVM-unit-testable with fakes: no Context, no
 * conversation, no Room.
 *
 * It IMPLEMENTS the neutral [TaskApprovalGate] so the coordinator/child path depends only on the
 * abstraction; this app-side concrete is the one place that knows the allowlist lives on an
 * Assistant-derived agent type and that the parent surface is `ChatService`.
 *
 * @param policyFor resolves the [TaskToolPolicy] for a task id (derived from the agent type's
 *   `AgentTypeSpec.toolPolicy`, ultimately `Assistant.subagentApprovalAllowlist`). A task with no
 *   known policy is treated as the empty allowlist — forward nothing — the safe default.
 * @param surface the parent's approval surface; only ALLOWLISTED requests ever reach it.
 * @param store records the auto-deny reason in the task summary for non-allowlisted tools.
 */
class TaskApprovalRouter(
    private val policyFor: suspend (taskId: Uuid) -> TaskToolPolicy,
    private val surface: ParentApprovalSurface,
    private val store: TaskRunStore = NoopTaskRunStore,
) : TaskApprovalGate {

    /**
     * Decide one child approval request.
     *
     * @return the parent's [TaskApprovalDecision] for an allowlisted tool, or
     *   [TaskApprovalDecision.Denied] for a non-allowlisted one (auto-deny). Every outcome
     *   resumes the child — the decision travels to it as the tool result; nothing here
     *   terminates the task.
     */
    override suspend fun await(taskId: Uuid, request: TaskApprovalRequest): TaskApprovalDecision {
        val policy = policyFor(taskId)
        if (!policy.forwardsApprovalFor(request.toolName)) {
            // Non-allowlisted: auto-deny WITHOUT touching the parent surface, and record why in the
            // task summary so the denial is visible to the parent (decision #2). No hidden
            // execution: a denied child tool never runs.
            val reason = autoDenyReason(request.toolName)
            store.appendEventSummary(
                taskId = taskId,
                summary = reason,
                kind = SUMMARY_KIND_APPROVAL_DENIED,
            )
            return TaskApprovalDecision.Denied(reason)
        }
        // Allowlisted: forward to the parent, namespaced taskId/childToolCallId, and suspend until
        // the parent decides. The surface is the existing approval path — no hidden execution while
        // pending, and approve-then-resume is exactly the parent's direct approved execution.
        //
        // The router also drives the task state machine through the round-trip (SPEC.md M4):
        // ApprovalRequested folds Running -> WaitingApproval BEFORE the suspension, so the persisted
        // state says exactly why the child is not progressing; ApprovalResolved folds it to
        // Resuming; ChildProgressed folds Resuming -> Running the moment the gate returns — that
        // return IS the child resuming (it executes the tool or consumes the denial). Without the
        // last event the run would strand in Resuming: the coordinator emits ChildProgressed only
        // once, on the FIRST child chunk, so nothing else takes the run back to Running and the
        // final FinalResult would be an illegal (ignored) edge from Resuming.
        store.applyEvent(taskId, TaskEvent.ApprovalRequested(request))
        val decision = surface.requestApproval(
            namespacedToolCallId = namespacedToolCallId(taskId, request.childToolCallId),
            request = request,
        )
        store.applyEvent(taskId, TaskEvent.ApprovalResolved(decision.approved))
        store.applyEvent(taskId, TaskEvent.ChildProgressed)
        // The DURABLE audit record of the decision (review mustFix #2). The parent-visible
        // pending part is a live-transcript projection only: the parent runtime's post-execution
        // snapshot does not contain it, so the next publish replaces the message and the part
        // vanishes (by design — a child tool call never joins the parent transcript, decision
        // #1). What must survive is the decision itself, and the task summary is where every
        // other child-approval fact (the auto-deny reason) already lives.
        store.appendEventSummary(
            taskId = taskId,
            summary = resolvedSummary(request.toolName, decision),
            kind = SUMMARY_KIND_APPROVAL_RESOLVED,
        )
        return decision
    }

    private fun autoDenyReason(toolName: String): String =
        "Child tool \"$toolName\" auto-denied: not on the subagent approval allowlist."

    /**
     * The durable record of the parent's decision. The full payload matters: recovery seeds an
     * interrupted run from the LAST event summary, and for an [TaskApprovalDecision.Answered]
     * decision the ANSWER is the child's actual tool result — reducing it to "approved" would
     * resume a process-death run without the one fact the child was waiting on.
     */
    private fun resolvedSummary(toolName: String, decision: TaskApprovalDecision): String = when (decision) {
        TaskApprovalDecision.Approved ->
            "Child tool \"$toolName\" approved by the parent."
        is TaskApprovalDecision.Answered ->
            "Child tool \"$toolName\" answered by the parent: ${decision.answer}"
        is TaskApprovalDecision.Denied ->
            "Child tool \"$toolName\" denied by the parent" +
                (if (decision.reason.isBlank()) "." else ": ${decision.reason}")
    }

    companion object {
        /** Summary kind recorded when a non-allowlisted child tool auto-denies. */
        const val SUMMARY_KIND_APPROVAL_DENIED: String = "approval_denied"

        /** Summary kind recording the parent's decision on a FORWARDED child approval. */
        const val SUMMARY_KIND_APPROVAL_RESOLVED: String = "approval_resolved"

        /** Namespaced id under which a forwarded child approval is keyed on the parent surface. */
        const val NAMESPACE_SEPARATOR: String = "/"

        /**
         * The id a forwarded child approval is keyed under on the parent's approval surface:
         * `taskId/childToolCallId`. This is how a child's pending approval is anchored INSIDE the
         * parent's `task` tool step rather than colliding with the parent's own tool call ids.
         */
        fun namespacedToolCallId(taskId: Uuid, childToolCallId: String): String =
            "$taskId$NAMESPACE_SEPARATOR$childToolCallId"

        /**
         * Whether a tool-call id arriving at `handleToolApproval` is a forwarded CHILD approval —
         * i.e. shaped `taskId/childToolCallId` with a parseable task UUID before the separator.
         * Provider-generated parent ids never carry this shape, so the parent approval path
         * (cancel-relaunch) and the child path (resolve-in-place, the generation stays live) can
         * be told apart structurally rather than by bookkeeping.
         */
        fun isNamespacedChildApprovalId(toolCallId: String): Boolean {
            val prefix = toolCallId.substringBefore(NAMESPACE_SEPARATOR, missingDelimiterValue = "")
            if (prefix.isEmpty()) return false
            return runCatching { Uuid.parse(prefix) }.isSuccess
        }
    }
}

/**
 * The parent's approval surface, as the [TaskApprovalRouter] needs it (DIP). The concrete binds
 * `ChatService`: it raises a parent-visible pending approval (a `UIMessagePart.Tool` in
 * `ToolApprovalState.Pending`, namespaced under the parent's `task` step) and suspends until the
 * user resolves it through `handleToolApproval`, returning the decision.
 *
 * Keeping this a one-method port — not the whole `ChatService` — is what lets the router be tested
 * with a fake surface that records what was forwarded and answers deterministically, no Android.
 */
interface ParentApprovalSurface {
    /**
     * Make [request] parent-visible under [namespacedToolCallId], suspend until the parent
     * decides, and return that [TaskApprovalDecision] (approve / answer / deny). The router only
     * ever calls this for an ALLOWLISTED tool.
     */
    suspend fun requestApproval(
        namespacedToolCallId: String,
        request: TaskApprovalRequest,
    ): TaskApprovalDecision
}
