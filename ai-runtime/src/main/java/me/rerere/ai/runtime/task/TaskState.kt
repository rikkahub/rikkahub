package me.rerere.ai.runtime.task

/**
 * Task lifecycle state machine (SPEC.md M1, transcribed from the approved design):
 *
 * ```
 * Created -> Queued -> Starting -> Running -> Succeeded
 *                                  Running -> WaitingApproval -> Resuming -> Running
 * any active state -> Failed | Cancelled | BudgetExhausted | Interrupted
 * Interrupted -> Resuming            (the ONLY edge out, explicit user resume)
 * ```
 *
 * [Succeeded], [Failed], [Cancelled], and [BudgetExhausted] are absorbing terminals: replaying
 * any event on them is the identity (recovery / event redelivery is idempotent). [Interrupted]
 * is NOT terminal but only [TaskEvent.ResumeRequested] leaves it — resume is user-explicit,
 * never automatic on startup (maintainer decisions #1/#3).
 */
sealed interface TaskState {
    /** True for the absorbing states; [Interrupted] is resumable, hence NOT terminal. */
    val isTerminal: Boolean
        get() = false

    /** The task run exists but its spawn tool call has not been accepted yet. */
    data object Created : TaskState

    /** The spawn tool call executed; the task waits for a concurrency slot. */
    data object Queued : TaskState

    /** The coordinator claimed a slot and is launching the child generation. */
    data object Starting : TaskState

    /** The child generation is producing events. */
    data object Running : TaskState

    /** An allowlisted child tool approval is pending on the parent's approval surface. */
    data class WaitingApproval(val request: TaskApprovalRequest) : TaskState

    /** The child is being (re)started: after an approval round-trip or an explicit resume. */
    data object Resuming : TaskState

    data class Succeeded(val summary: String) : TaskState {
        override val isTerminal: Boolean get() = true
    }

    data class Failed(val error: String) : TaskState {
        override val isTerminal: Boolean get() = true
    }

    data object Cancelled : TaskState {
        override val isTerminal: Boolean get() = true
    }

    data class BudgetExhausted(val breach: TaskBudgetBreach) : TaskState {
        override val isTerminal: Boolean get() = true
    }

    /**
     * The run was cut off (process death). Carries the progress summary the resume injects as
     * context — the full child transcript is never persisted (decision #1).
     */
    data class Interrupted(val progressSummary: String) : TaskState
}

/**
 * An allowlisted child tool call waiting for the parent's decision, namespaced
 * `taskId/childToolCallId` on the parent approval surface. [toolName] is whatever name the
 * child's pool carries — this neutral domain names no concrete tool.
 */
data class TaskApprovalRequest(
    val childToolCallId: String,
    val toolName: String,
)

/** Everything that can happen to a task run; [TaskStateReducer] folds these over [TaskState]. */
sealed interface TaskEvent {
    /** The spawn tool call was accepted. */
    data object Enqueued : TaskEvent

    /** The coordinator claimed a per-parent/global concurrency slot. */
    data object SlotClaimed : TaskEvent

    /** The child produced an event — proof it is observably running (also confirms a resume). */
    data object ChildProgressed : TaskEvent

    /** An allowlisted child tool needs parent approval. */
    data class ApprovalRequested(val request: TaskApprovalRequest) : TaskEvent

    /**
     * The parent decided. A denial does NOT terminate the task: it is delivered to the child
     * as the tool result, so both outcomes resume the child (decision #2).
     */
    data class ApprovalResolved(val approved: Boolean) : TaskEvent

    /** The child finished with a final answer. */
    data class FinalResult(val summary: String) : TaskEvent

    /** The child failed (provider error, unexpected exception). */
    data class ExecutionFailed(val error: String) : TaskEvent

    /** The user (or the parent's cancel cascade) stopped the task. */
    data object CancelRequested : TaskEvent

    /** A budget cap was exceeded ([TaskBudget.firstBreach]). */
    data class BudgetExceeded(val breach: TaskBudgetBreach) : TaskEvent

    /** Recovery marked the run as cut off (process death), carrying the progress summary. */
    data class ProcessInterrupted(val progressSummary: String) : TaskEvent

    /** The user explicitly asked to resume an [TaskState.Interrupted] run. */
    data object ResumeRequested : TaskEvent
}

/**
 * Pure, total reducer over the task state machine. Illegal (state, event) pairs return the
 * state unchanged, which makes terminals absorbing and event redelivery idempotent — the
 * TASK_STATE_LEGAL invariant. No platform imports, no IO, no clock: budget/wall-time decisions
 * happen OUTSIDE and arrive here only as [TaskEvent.BudgetExceeded].
 */
object TaskStateReducer {

    fun reduce(state: TaskState, event: TaskEvent): TaskState = when (state) {
        TaskState.Created -> when (event) {
            TaskEvent.Enqueued -> TaskState.Queued
            else -> failOrIgnore(state, event)
        }

        TaskState.Queued -> when (event) {
            TaskEvent.SlotClaimed -> TaskState.Starting
            else -> failOrIgnore(state, event)
        }

        TaskState.Starting -> when (event) {
            TaskEvent.ChildProgressed -> TaskState.Running
            else -> failOrIgnore(state, event)
        }

        TaskState.Running -> when (event) {
            is TaskEvent.ApprovalRequested -> TaskState.WaitingApproval(event.request)
            is TaskEvent.FinalResult -> TaskState.Succeeded(event.summary)
            else -> failOrIgnore(state, event)
        }

        is TaskState.WaitingApproval -> when (event) {
            // Approve and deny both resume the child; the decision payload travels to the
            // child as the tool result, not into the lifecycle state.
            is TaskEvent.ApprovalResolved -> TaskState.Resuming
            else -> failOrIgnore(state, event)
        }

        TaskState.Resuming -> when (event) {
            TaskEvent.ChildProgressed -> TaskState.Running
            else -> failOrIgnore(state, event)
        }

        // The ONLY edge out of Interrupted (decisions #1/#3): explicit user resume. Failure /
        // cancel / budget events on an interrupted run are stale echoes of the dead handle and
        // must not destroy resumability.
        is TaskState.Interrupted -> when (event) {
            TaskEvent.ResumeRequested -> TaskState.Resuming
            else -> state
        }

        // Absorbing terminals: replaying anything is the identity.
        is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled, is TaskState.BudgetExhausted -> state
    }

    /**
     * The failure edges every ACTIVE state shares (`any active state -> Failed | Cancelled |
     * BudgetExhausted | Interrupted`); anything else is an illegal edge for the caller's state
     * and is ignored.
     */
    private fun failOrIgnore(state: TaskState, event: TaskEvent): TaskState = when (event) {
        is TaskEvent.ExecutionFailed -> TaskState.Failed(event.error)
        TaskEvent.CancelRequested -> TaskState.Cancelled
        is TaskEvent.BudgetExceeded -> TaskState.BudgetExhausted(event.breach)
        is TaskEvent.ProcessInterrupted -> TaskState.Interrupted(event.progressSummary)
        else -> state
    }
}
