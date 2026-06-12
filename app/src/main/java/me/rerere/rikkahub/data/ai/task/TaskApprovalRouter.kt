package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.task.TaskApprovalRequest
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
     * @return true when the parent approved an allowlisted tool, false when the parent denied an
     *   allowlisted tool OR the tool was auto-denied (not on the allowlist). Both false outcomes
     *   resume the child with the denial as its tool result — neither terminates the task.
     */
    override suspend fun await(taskId: Uuid, request: TaskApprovalRequest): Boolean {
        val policy = policyFor(taskId)
        if (!policy.forwardsApprovalFor(request.toolName)) {
            // Non-allowlisted: auto-deny WITHOUT touching the parent surface, and record why in the
            // task summary so the denial is visible to the parent (decision #2). No hidden
            // execution: a denied child tool never runs.
            store.appendEventSummary(
                taskId = taskId,
                summary = autoDenyReason(request.toolName),
                kind = SUMMARY_KIND_APPROVAL_DENIED,
            )
            return false
        }
        // Allowlisted: forward to the parent, namespaced taskId/childToolCallId, and suspend until
        // the parent decides. The surface is the existing approval path — no hidden execution while
        // pending, and approve-then-resume is exactly the parent's direct approved execution.
        return surface.requestApproval(
            namespacedToolCallId = namespacedToolCallId(taskId, request.childToolCallId),
            request = request,
        )
    }

    private fun autoDenyReason(toolName: String): String =
        "Child tool \"$toolName\" auto-denied: not on the subagent approval allowlist."

    companion object {
        /** Summary kind recorded when a non-allowlisted child tool auto-denies. */
        const val SUMMARY_KIND_APPROVAL_DENIED: String = "approval_denied"

        /** Namespaced id under which a forwarded child approval is keyed on the parent surface. */
        const val NAMESPACE_SEPARATOR: String = "/"

        /**
         * The id a forwarded child approval is keyed under on the parent's approval surface:
         * `taskId/childToolCallId`. This is how a child's pending approval is anchored INSIDE the
         * parent's `task` tool step rather than colliding with the parent's own tool call ids.
         */
        fun namespacedToolCallId(taskId: Uuid, childToolCallId: String): String =
            "$taskId$NAMESPACE_SEPARATOR$childToolCallId"
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
     * decides, and return that decision (true = approved, false = denied). The router only ever
     * calls this for an ALLOWLISTED tool.
     */
    suspend fun requestApproval(
        namespacedToolCallId: String,
        request: TaskApprovalRequest,
    ): Boolean
}
