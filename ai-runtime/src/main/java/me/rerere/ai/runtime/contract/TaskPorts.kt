package me.rerere.ai.runtime.contract

import me.rerere.ai.runtime.board.WorkItem
import me.rerere.ai.runtime.board.WorkItemAction
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.task.AgentTypeSpec
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskEvent
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Neutral ports for the task tool & task-management delivery (SPEC.md M1). Like the other
 * contract ports in this package, these name no concrete tool/source and carry no platform
 * type: the `:app` composition root binds them over the spawnable-assistant registry, the
 * task-run repository, the parent approval surface, a monotonic clock, and the board
 * repository respectively. The runtime/tool side depends ONLY on these abstractions.
 */

/**
 * Resolves the spawnable agent types a parent may run a task as. Derived from spawnable
 * assistants in v1 (spec assumption 2); the registry shape keeps non-assistant presets
 * possible later without touching consumers.
 */
interface TaskAgentRegistry {
    /** Every agent type currently available to spawn, in presentation order. */
    suspend fun agentTypes(): List<AgentTypeSpec>

    /** The agent type with the given [AgentTypeSpec.id], or null when unknown/no-longer-spawnable. */
    suspend fun agentType(id: String): AgentTypeSpec? = agentTypes().firstOrNull { it.id == id }
}

/**
 * Receives every lifecycle event of a task run, in emission order per task. The binding side
 * folds them through `TaskStateReducer` and persists the summary-only progress record
 * (maintainer decision #1) — the emitter never persists anything itself.
 */
interface TaskEventSink {
    suspend fun emit(taskId: Uuid, event: TaskEvent)
}

/**
 * Forwards a child tool approval request and suspends until a decision exists: ALLOWLISTED
 * tools reach the parent's approval surface; everything else auto-denies with the reason
 * recorded in the task summary (maintainer decision #2).
 *
 * @return the parent's [TaskApprovalDecision]. Every shape resumes the child: Approved runs the
 *   real tool, Answered IS the tool result (the tool never executes), Denied travels to the
 *   child as the denial result.
 */
interface TaskApprovalGate {
    suspend fun await(taskId: Uuid, request: TaskApprovalRequest): TaskApprovalDecision
}

/**
 * Monotonic clock for wall-time budget accounting. Distinct from [RuntimeClock] (wall-clock
 * timestamps for transcripts): budget elapsed time must be immune to wall-clock jumps, so the
 * binding uses a monotonic source and tests inject a fixed/stepping one.
 */
interface TaskBudgetClock {
    /**
     * Time elapsed since an arbitrary fixed origin. Differences between two readings feed
     * `TaskBudgetUsage.elapsed`; the absolute value is meaningless.
     */
    fun monotonicNow(): Duration
}

/**
 * What a caller provides to create one work item; everything server-side (id, conversation
 * scope, status = Pending, timestamps) is assigned by the binding repository.
 *
 * @param blockedBy ids of EXISTING items that must finish before this one may proceed. The
 *   repository validates them (existence + acyclicity) atomically with the insert.
 */
data class WorkItemDraft(
    val subject: String,
    val description: String = "",
    val activeForm: String? = null,
    val blockedBy: List<Uuid> = emptyList(),
)

/**
 * A partial update of one work item: only non-null fields change ([addBlockedBy] is additive).
 * Status changes travel as an explicit [WorkItemAction] — never a raw target status — so the
 * `WorkItemTransitionValidator` stays the single legality authority (maintainer decision #4).
 */
data class WorkItemPatch(
    val id: Uuid,
    val subject: String? = null,
    val description: String? = null,
    val activeForm: String? = null,
    val action: WorkItemAction? = null,
    val addBlockedBy: List<Uuid> = emptyList(),
)

/** One work item plus its incoming dependency edges, as read through [TaskBoardPort]. */
data class BoardItemSnapshot(
    val item: WorkItem,
    val blockedBy: List<Uuid> = emptyList(),
)

/**
 * Outcome of a board mutation. [Rejected] is an EXPECTED domain outcome (cycle insertion,
 * illegal transition, unknown id) the caller surfaces to its user/model — never an exception:
 * a rejected board edit must not abort the chat turn that attempted it.
 */
sealed interface BoardMutationResult {
    /** The mutation was applied; [snapshot] is the item's new state. */
    data class Accepted(val snapshot: BoardItemSnapshot) : BoardMutationResult

    /** The repository refused the mutation; [reason] is caller-surfaceable. */
    data class Rejected(val reason: String) : BoardMutationResult
}

/**
 * Neutral port over ONE conversation's work-item board. The conversation scope is bound at the
 * composition root (the port is constructed per generation, like the tool catalog), so tools
 * built on it never see a conversation id. ALL invariants — legal transitions, cycle rejection,
 * atomic claims — are enforced behind this port in the repository layer; board tools and the
 * board UI share that single path (maintainer decision #4).
 */
interface TaskBoardPort {
    suspend fun create(draft: WorkItemDraft): BoardMutationResult

    /** The item with the given id, or null when it does not exist on this board. */
    suspend fun get(id: Uuid): BoardItemSnapshot?

    /** Items on this board, optionally filtered to [statuses]; null = no status filter. */
    suspend fun list(statuses: Set<WorkItemStatus>? = null): List<BoardItemSnapshot>

    suspend fun update(patch: WorkItemPatch): BoardMutationResult
}
