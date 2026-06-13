package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * The single in-memory home for live subagent execution handles (SPEC.md M4, decision: live
 * coroutine handles are NEVER persisted — only board claims/leases are).
 *
 * Two invariants make this registry correct:
 *
 *  1. **Structural cancel cascade.** Every handle's [ExecutionHandle.job] is created as a CHILD of
 *     the parent generation job (`Job(parent = parentJob)`), so `stopGeneration`'s
 *     `parentJob.cancel()` (ChatService.kt:1822) tears down all registered handles with zero
 *     bookkeeping here — the coroutine tree does the cascade. We never iterate-and-cancel as a
 *     band-aid for missing parenting.
 *  2. **A legal handle state machine.** `Created -> Running -> Completed | Failed | Stopped`.
 *     Terminals are absorbing; an illegal edge (e.g. `Created -> Completed`, or any write after a
 *     terminal) is a no-op, mirroring the task reducer's TASK_STATE_LEGAL discipline so a stale
 *     event from a torn-down coroutine can never resurrect or regress a finished handle.
 *
 * Thread-safe: handles live in a [ConcurrentHashMap] and each mutation is a [ConcurrentHashMap.compute]
 * (atomic read-modify-write keyed by handle id), so concurrent child events and a parent cancel
 * cannot interleave into a torn record.
 */
class ExecutionHandleRegistry(
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val handles = ConcurrentHashMap<String, ExecutionHandle>()

    /**
     * Create a live handle for a spawned subagent. The handle's [Job] is a child of [parentJob]
     * (the conversation's generation job), establishing the cancel cascade structurally before any
     * child coroutine launches on it.
     */
    fun register(
        conversationId: Uuid,
        assistantId: Uuid,
        parentJob: Job,
        id: String = Uuid.random().toString(),
    ): ExecutionHandle {
        val timestamp = now()
        val handle = ExecutionHandle(
            id = id,
            conversationId = conversationId,
            kind = ExecutionHandleKind.Subagent,
            status = ExecutionHandleStatus.Created,
            assistantId = assistantId,
            workItemIds = emptySet(),
            job = Job(parent = parentJob),
            output = "",
            result = null,
            error = null,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        handles[id] = handle
        return handle
    }

    fun get(id: String): ExecutionHandle? = handles[id]

    fun listByConversation(conversationId: Uuid): List<ExecutionHandle> =
        handles.values.filter { it.conversationId == conversationId }

    /** `Created -> Running`. No-op from any other state. */
    fun markRunning(id: String): Unit = transition(id) { handle ->
        if (handle.status == ExecutionHandleStatus.Created) {
            handle.copy(status = ExecutionHandleStatus.Running)
        } else {
            handle
        }
    }

    /** `Running -> Completed`, carrying the final result. No-op unless currently `Running`. */
    fun markCompleted(id: String, result: String): Unit = transition(id) { handle ->
        if (handle.status == ExecutionHandleStatus.Running) {
            handle.copy(status = ExecutionHandleStatus.Completed, result = result)
        } else {
            handle
        }
    }

    /** `Running -> Failed`, carrying the error. No-op unless currently `Running`. */
    fun markFailed(id: String, error: String): Unit = transition(id) { handle ->
        if (handle.status == ExecutionHandleStatus.Running) {
            handle.copy(status = ExecutionHandleStatus.Failed, error = error)
        } else {
            handle
        }
    }

    /**
     * Stop a handle: cancel its [Job] (which cascades to whatever the child launched on it) and
     * move to the `Stopped` terminal. Legal from `Created` or `Running`; a no-op on a terminal.
     * The cancel runs even on the no-op path is avoided — a finished handle's job is already done.
     */
    fun stop(id: String): Unit = transition(id) { handle ->
        if (handle.status.isTerminal) {
            handle
        } else {
            handle.job.cancel()
            handle.copy(status = ExecutionHandleStatus.Stopped)
        }
    }

    /** Append to the live output buffer. Ignored once the handle is terminal. */
    fun appendOutput(id: String, chunk: String): Unit = transition(id) { handle ->
        if (handle.status.isTerminal) handle else handle.copy(output = handle.output + chunk)
    }

    /** Record a board claim this handle owns. Idempotent; ignored once terminal. */
    fun attachWorkItem(id: String, workItemId: Uuid): Unit = transition(id) { handle ->
        if (handle.status.isTerminal) handle else handle.copy(workItemIds = handle.workItemIds + workItemId)
    }

    /**
     * Drop a handle from the registry (it is never persisted, so this is the only cleanup). The
     * handle's [Job] is COMPLETED here: it was created as a child of the parent generation job,
     * and a child Job that never completes keeps its parent in `completing` forever — so a dropped
     * handle would otherwise pin the whole generation. Completion (not cancel) lets any structural
     * descendants still running finish under normal structured-concurrency rules.
     */
    fun unregister(id: String) {
        (handles.remove(id)?.job as? CompletableJob)?.complete()
    }

    /**
     * Atomic read-modify-write keyed by handle id. [mutate] is pure over the record; the
     * `updatedAt` bump and the store happen under [ConcurrentHashMap.compute] so concurrent
     * mutations serialize per handle. Returning the same instance from [mutate] means "no legal
     * edge" — we skip the timestamp bump so a no-op leaves the record byte-identical.
     */
    private inline fun transition(id: String, crossinline mutate: (ExecutionHandle) -> ExecutionHandle) {
        handles.compute(id) { _, existing ->
            existing ?: return@compute null
            val next = mutate(existing)
            if (next === existing) existing else next.copy(updatedAt = now())
        }
    }
}

/** The kind of execution a handle drives. Only [Subagent] exists in v1 (SPEC.md M4). */
enum class ExecutionHandleKind {
    Subagent,
}

/**
 * Handle lifecycle: `Created -> Running -> Completed | Failed | Stopped`. The three terminals are
 * absorbing — once reached, no event regresses the handle (TASK_STATE_LEGAL discipline applied to
 * the in-memory live handle).
 */
enum class ExecutionHandleStatus {
    Created,
    Running,
    Completed,
    Failed,
    Stopped;

    val isTerminal: Boolean
        get() = this == Completed || this == Failed || this == Stopped
}

/**
 * One live subagent run. Immutable snapshot — the registry replaces the whole record on every
 * mutation, so a handle reference a caller holds is a stable point-in-time view (the registry's
 * [ExecutionHandleRegistry.job] reference is shared and stays valid for cancellation).
 *
 * @param id stable handle id; also the board [me.rerere.rikkahub.data.repository.BoardActor.handleId]
 *   under which this handle's claims are owned (orphan recovery releases by this id).
 * @param job the child coroutine job; a structural child of the parent generation job so
 *   `stopGeneration` cascades. The same [Job] instance survives across snapshot replacements.
 * @param workItemIds the board items this handle currently claims (multiple allowed — decision #5).
 * @param output the live partial transcript buffer (UI mirror); the persisted summary lives in
 *   `TaskRunEntity`, never here.
 */
data class ExecutionHandle(
    val id: String,
    val conversationId: Uuid,
    val kind: ExecutionHandleKind,
    val status: ExecutionHandleStatus,
    val assistantId: Uuid,
    val workItemIds: Set<Uuid>,
    val job: Job,
    val output: String,
    val result: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
