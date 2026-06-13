package me.rerere.ai.runtime.contract

import kotlin.uuid.Uuid

/**
 * Neutral ports for task scheduling (SPEC.md M1). Like the other contract ports in this package,
 * these name no concrete tool/source and carry no platform type: the `:app` composition root binds
 * a [TaskSchedulePort] per generation over the schedule repository, closed over the conversation
 * scope and the owner class (the `SchedulePortAdapter` pattern, mirroring how [TaskBoardPort] is
 * bound over one conversation's board). The runtime/tool side depends ONLY on these abstractions —
 * no `Context`, no conversation id ever crosses this surface.
 *
 * The recurrence spec travels as its already-serialized JSON form ([ScheduleDraft.recurrenceSpec]):
 * the typed `RecurrenceSpec` and its in-zone next-occurrence derivation live in the pure
 * `schedule/Recurrence.kt` module, off the neutral port, so the contract stays free of recurrence
 * math and the persisted `recurrence_spec` TEXT column is a passthrough.
 */

/** Whether a schedule fires once or on a repeating cadence. */
enum class ScheduleKind { ONE_SHOT, RECURRING }

/**
 * Who authored a schedule. The active-schedule cap is computed per owner class so an agent cannot
 * starve the user's quota and vice-versa (spec assumption 4). [USER] = UI-created, [AGENT] =
 * tool-created (the port adapter knows the caller is an agent generation).
 */
enum class ScheduleOwner { USER, AGENT }

/**
 * How a recurring schedule handles windows missed while the process was dead. v1 ships only
 * [FIRE_ONCE_AND_COALESCE]: a recurring schedule overdue by N intervals fires exactly once on the
 * next opportunity and advances `next_fire_at` to the first occurrence strictly after now — never
 * replaying every skipped window.
 */
enum class MisfirePolicy { FIRE_ONCE_AND_COALESCE }

/**
 * What a caller provides to create one schedule; everything server-side (id, conversation scope,
 * owner, timestamps, `next_fire_at` derivation, enabled flag) is assigned by the binding repository.
 *
 * @param firstFireAt epoch millis, wall clock — the first (or only) time the schedule fires.
 * @param timeZoneId IANA zone id; recurrence is computed in this zone so a "daily 09:00" survives DST.
 * @param recurrenceSpec already-serialized JSON `RecurrenceSpec`; required iff [kind] == RECURRING,
 *   ignored for ONE_SHOT. The repository parses/validates it via the pure recurrence module.
 */
data class ScheduleDraft(
    val targetAssistantId: Uuid,
    val prompt: String,
    val kind: ScheduleKind,
    val firstFireAt: Long,
    val timeZoneId: String,
    val recurrenceSpec: String? = null,
    val misfirePolicy: MisfirePolicy = MisfirePolicy.FIRE_ONCE_AND_COALESCE,
)

/**
 * The full persisted state of one schedule as read through [TaskSchedulePort]. Carries no
 * conversation id: the snapshot is always already scoped to the bound conversation, so a consumer
 * of the port never sees (or needs) the parent id.
 */
data class ScheduleSnapshot(
    val id: Uuid,
    val targetAssistantId: Uuid,
    val prompt: String,
    val owner: ScheduleOwner,
    val kind: ScheduleKind,
    val firstFireAt: Long,
    val nextFireAt: Long,
    val timeZoneId: String,
    val recurrenceSpec: String?,
    val misfirePolicy: MisfirePolicy,
    val enabled: Boolean,
    val lastFiredAt: Long?,
    val lastTaskRunId: Uuid?,
    val runningTaskRunId: Uuid?,
)

/**
 * Outcome of a schedule mutation. [Rejected] is an EXPECTED domain outcome (unknown/unspawnable
 * target, cap breach, sub-minimum interval, over-length prompt, cross-conversation id) the caller
 * surfaces to its user/model — never an exception: a rejected schedule edit must not abort the chat
 * turn that attempted it (mirrors [BoardMutationResult]).
 */
sealed interface ScheduleMutationResult {
    /** The mutation was applied; [snapshot] is the schedule's new state. */
    data class Accepted(val snapshot: ScheduleSnapshot) : ScheduleMutationResult

    /** The repository refused the mutation; [reason] is caller-surfaceable. */
    data class Rejected(val reason: String) : ScheduleMutationResult
}

/**
 * Neutral port over ONE conversation's schedules. The conversation scope and owner are bound at the
 * composition root (the port is constructed per generation, like the tool catalog), so tools built
 * on it never see either. ALL invariants — target spawnable, per-conversation/per-user caps,
 * minimum recurring interval, prompt bound, conversation scoping — are enforced behind this port in
 * the repository layer; schedule tools and the schedule UI share that single path.
 */
interface TaskSchedulePort {
    suspend fun create(draft: ScheduleDraft): ScheduleMutationResult

    /** Schedules on this conversation, in presentation order. */
    suspend fun list(): List<ScheduleSnapshot>

    /** Deletes the schedule with [id] iff it belongs to the bound conversation; rejects otherwise. */
    suspend fun delete(id: Uuid): ScheduleMutationResult
}
