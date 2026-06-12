package me.rerere.ai.runtime.board

import kotlin.uuid.Uuid

/**
 * Pure (Android-free) work-item board domain (SPEC.md M1). Status machine, transcribed from the
 * approved design:
 *
 * ```
 * Pending -> InProgress -> Completed
 * Pending | InProgress | Completed -> Deleted
 * InProgress -> Pending     ONLY via explicit release (user release / orphan recovery)
 * Completed -> Pending      ONLY via explicit reopen
 * ```
 *
 * [WorkItemStatus.Deleted] is absorbing. The "only via explicit X" rules are structural, not
 * conventions: a transition is requested as a [WorkItemAction], so a regression edge simply does
 * not exist without the explicit [WorkItemAction.Release] / [WorkItemAction.Reopen] intent —
 * the TerminalStatusMonotonicity invariant.
 */
enum class WorkItemStatus { Pending, InProgress, Completed, Deleted }

/**
 * The intent behind a status change. Callers (board tools, UI, orphan recovery) request actions,
 * never raw target statuses, so "explicit release/reopen" is carried by the type and the
 * validator stays caller-agnostic (maintainer decision #4: one enforcement path for everyone).
 */
sealed interface WorkItemAction {
    /** Take ownership and start working: `Pending -> InProgress`. */
    data object Claim : WorkItemAction

    /** Finish the work: `InProgress -> Completed`. */
    data object Complete : WorkItemAction

    /** Explicitly give the item back (user release / orphan recovery): `InProgress -> Pending`. */
    data object Release : WorkItemAction

    /** Explicitly reopen finished work: `Completed -> Pending`. */
    data object Reopen : WorkItemAction

    /** Remove the item from the board: any non-deleted status `-> Deleted`. */
    data object Delete : WorkItemAction
}

/** Outcome of validating one requested transition. */
sealed interface WorkItemTransitionResult {
    /** The transition is legal; the item moves to [to]. */
    data class Allowed(val to: WorkItemStatus) : WorkItemTransitionResult

    /** The transition is illegal; the item must stay at [from]. [reason] is caller-surfaceable. */
    data class Rejected(
        val from: WorkItemStatus,
        val action: WorkItemAction,
    ) : WorkItemTransitionResult {
        val reason: String
            get() = "illegal work-item transition: ${action::class.simpleName} on $from"
    }
}

/**
 * Pure, total validator over the work-item status machine. This is the SINGLE source of truth
 * for legal transitions — the repository layer applies it for board tools and the UI alike;
 * tool-handler-only validation is forbidden (maintainer decision #4).
 */
object WorkItemTransitionValidator {

    fun transition(from: WorkItemStatus, action: WorkItemAction): WorkItemTransitionResult = when (from) {
        WorkItemStatus.Pending -> when (action) {
            WorkItemAction.Claim -> WorkItemTransitionResult.Allowed(WorkItemStatus.InProgress)
            WorkItemAction.Delete -> WorkItemTransitionResult.Allowed(WorkItemStatus.Deleted)
            else -> WorkItemTransitionResult.Rejected(from, action)
        }

        WorkItemStatus.InProgress -> when (action) {
            WorkItemAction.Complete -> WorkItemTransitionResult.Allowed(WorkItemStatus.Completed)
            WorkItemAction.Release -> WorkItemTransitionResult.Allowed(WorkItemStatus.Pending)
            WorkItemAction.Delete -> WorkItemTransitionResult.Allowed(WorkItemStatus.Deleted)
            else -> WorkItemTransitionResult.Rejected(from, action)
        }

        WorkItemStatus.Completed -> when (action) {
            WorkItemAction.Reopen -> WorkItemTransitionResult.Allowed(WorkItemStatus.Pending)
            WorkItemAction.Delete -> WorkItemTransitionResult.Allowed(WorkItemStatus.Deleted)
            else -> WorkItemTransitionResult.Rejected(from, action)
        }

        // Absorbing: nothing leaves Deleted, not even another delete.
        WorkItemStatus.Deleted -> WorkItemTransitionResult.Rejected(from, action)
    }
}

/**
 * One work item on a per-conversation board — the neutral DTO the board tools and the UI share.
 * Persistence concerns (timestamps, lease expiry) live on the `:app` Room entity; ownership is
 * domain (a claim couples [status] = [WorkItemStatus.InProgress] with an owner, enforced
 * transactionally in the repository — SingleOwnerClaim, M2).
 *
 * @param activeForm present-continuous label shown while the item is in progress.
 * @param ownerHandleId the execution handle currently holding the claim, null when unclaimed.
 * @param ownerName display name of the claim holder (user or spawned subagent).
 */
data class WorkItem(
    val id: Uuid,
    val conversationId: Uuid,
    val subject: String,
    val description: String = "",
    val activeForm: String? = null,
    val status: WorkItemStatus = WorkItemStatus.Pending,
    val ownerHandleId: String? = null,
    val ownerName: String? = null,
)
