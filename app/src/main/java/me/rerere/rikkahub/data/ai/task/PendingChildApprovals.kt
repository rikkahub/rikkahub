package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableDeferred
import me.rerere.ai.runtime.task.TaskApprovalDecision
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-memory rendezvous between a suspended child approval ([ParentApprovalSurface] awaiting
 * the parent's decision) and `ChatService.handleToolApproval` resolving it. Keyed by the
 * namespaced `taskId/childToolCallId`; one entry per in-flight forwarded approval.
 *
 * Never persisted, mirroring the execution-handle rule: a pending entry is only meaningful while
 * the awaiting child coroutine is alive. If the generation is cancelled mid-wait, [await]'s
 * cancellation removes the entry; a later resolve for that id is then a recorded no-op (false) —
 * a decision for a dead waiter must not invent an approval out of thin air.
 */
class PendingChildApprovals {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<TaskApprovalDecision>>()

    /**
     * Register [namespacedToolCallId] as pending. SEPARATE from [await] (review mustFix #1) so
     * the surface can order register -> make-visible -> await: the moment the pending part is
     * user-visible a decision can arrive, and a resolve that lands before the deferred existed
     * would be dropped — leaving the child suspended forever on a decision the user already made.
     * With registration first, an early [resolve] simply completes the deferred and [await]
     * returns immediately.
     */
    fun register(namespacedToolCallId: String) {
        check(pending.putIfAbsent(namespacedToolCallId, CompletableDeferred()) == null) {
            "duplicate pending child approval: $namespacedToolCallId"
        }
    }

    /**
     * Suspend until [resolve] supplies the parent's decision for a previously [register]ed id.
     * The entry is removed on EVERY exit — decision or cancellation — so the map never
     * accumulates dead waiters.
     */
    suspend fun await(namespacedToolCallId: String): TaskApprovalDecision {
        val deferred = checkNotNull(pending[namespacedToolCallId]) {
            "await without register: $namespacedToolCallId"
        }
        return try {
            deferred.await()
        } finally {
            pending.remove(namespacedToolCallId)
        }
    }

    /**
     * Drop a registered entry whose await will never run (visibility failed before suspension).
     * A decision already delivered to it is intentionally discarded — the request was never
     * shown, so there is nothing it could legitimately approve.
     */
    fun abandon(namespacedToolCallId: String) {
        pending.remove(namespacedToolCallId)
    }

    /**
     * Deliver the parent's decision to the waiter, if it is still alive. Returns true when a
     * waiter was resumed; false when no waiter exists (already resolved, cancelled, or a stale
     * id) — the caller treats that as a cosmetic-only resolution.
     */
    fun resolve(namespacedToolCallId: String, decision: TaskApprovalDecision): Boolean =
        pending[namespacedToolCallId]?.complete(decision) ?: false

    /** Whether a live waiter exists for the id. */
    fun isPending(namespacedToolCallId: String): Boolean = pending.containsKey(namespacedToolCallId)
}
