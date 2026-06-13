package me.rerere.rikkahub.data.repository

import kotlinx.serialization.json.Json
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.contract.MisfirePolicy
import me.rerere.ai.runtime.schedule.Recurrence
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.data.db.dao.TaskScheduleDAO
import me.rerere.rikkahub.data.db.entity.TaskScheduleEntity
import me.rerere.rikkahub.data.model.Assistant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * The outcome of a winning [TaskScheduleRepository.claimDue]: the new run's id the worker will drive
 * [TaskCoordinator.run] under, plus the schedule's post-claim [snapshot] (already advanced/disabled).
 * The win is carried in this VALUE — a non-null [ScheduleClaim] means "you won this window" — so the
 * caller never has to re-read the row and infer the win from post-state, exactly as `claimResume`
 * reports its win as a `Boolean` rather than via a follow-up read.
 */
data class ScheduleClaim(
    val runId: Uuid,
    val snapshot: ScheduleSnapshot,
)

/**
 * The SINGLE legality path for task schedules (SPEC.md M3): the schedule tools and the schedule UI
 * both mutate through this repository, so legality never depends on the caller. Each public method
 * runs inside exactly one [BoardTransactionRunner] transaction and validates EVERYTHING before it
 * writes anything — there is no partial write to roll back.
 *
 * Gates enforced here, nowhere else (SPEC.md "Repository Safety Gates"):
 * - **Target spawnable.** [ScheduleDraft.targetAssistantId] must resolve to an existing
 *   [Assistant] with `spawnable = true`. The lookup is an injected `suspend (Uuid) -> Assistant?`
 *   (DIP): the repository never imports the settings store, mirroring how `TaskCoordinator` takes
 *   injected lookups, so it stays a pure persistence concern.
 * - **Active-schedule caps.** Enabled schedules are capped per conversation
 *   ([MAX_ACTIVE_PER_CONVERSATION]) AND per owner class ([MAX_ACTIVE_PER_USER]); the owner split
 *   keeps an agent from starving the user's quota and vice-versa (spec assumption 4).
 * - **Minimum recurring interval.** A `RECURRING` draft's interval must be
 *   >= [MIN_RECURRENCE_INTERVAL_MILLIS]; a malformed or missing spec is rejected too. This prevents
 *   a runaway tight loop chewing battery and aligns with WorkManager's own 15-minute floor.
 * - **Prompt bound.** `prompt.length <= ` [MAX_PROMPT_CHARS].
 * - **Scoping.** [list] and [delete] are scoped to the bound conversation; a [delete] of an id not
 *   in this conversation REJECTS (never silently deletes cross-conversation).
 *
 * Domain rejections return [ScheduleMutationResult.Rejected] — an EXPECTED outcome the caller
 * surfaces to its user/model. Never an exception: a rejected schedule edit must not abort the chat
 * turn that attempted it (mirrors [TaskBoardRepository]'s `BoardMutationResult`).
 *
 * The firing side ([claimDue]/[finishRun]) is the same single-writer discipline: [claimDue] is the
 * atomic claim-and-advance a worker calls when a schedule's window is due — one transaction that
 * decides the single winner, advances/disables the row, and reports the win DIRECTLY as a
 * [ScheduleClaim] (mirrors `TaskRunRepository.claimResume`). [finishRun] clears the in-flight marker
 * once the spawned run reaches a terminal state.
 */
class TaskScheduleRepository(
    private val dao: TaskScheduleDAO,
    private val transactions: BoardTransactionRunner,
    private val resolveAssistant: suspend (Uuid) -> Assistant?,
    private val json: Json = Json,
    private val now: () -> Long = System::currentTimeMillis,
    // WorkManager firing seams (SPEC.md M5 / task T9), injected so the repository stays a pure
    // persistence concern with no compile-time edge to WorkManager (DIP). They default to no-ops so
    // JVM repository tests — which fake Room and never touch the transport — construct the repository
    // unchanged. A create ENQUEUES the schedule's first fire; a delete CANCELS its unique work chain,
    // each AFTER the transaction commits (never enqueue then roll back).
    private val onScheduleCreated: (Uuid, Long) -> Unit = { _, _ -> },
    private val onScheduleDeleted: (Uuid) -> Unit = { },
) {
    /**
     * Create one schedule for [conversationId] owned by [owner]. Validates every gate before any
     * write; the first failing gate returns [ScheduleMutationResult.Rejected] and the transaction
     * touches no row. On acceptance, the schedule's first fire is enqueued via [onScheduleCreated]
     * AFTER the transaction commits, so a rolled-back create never leaves a phantom pending fire.
     */
    suspend fun create(
        conversationId: Uuid,
        owner: ScheduleOwner,
        draft: ScheduleDraft,
    ): ScheduleMutationResult {
        val result = transactions.inTransaction { createInTransaction(conversationId, owner, draft) }
        if (result is ScheduleMutationResult.Accepted) {
            onScheduleCreated(result.snapshot.id, result.snapshot.nextFireAt)
        }
        return result
    }

    private suspend fun createInTransaction(
        conversationId: Uuid,
        owner: ScheduleOwner,
        draft: ScheduleDraft,
    ): ScheduleMutationResult {
        if (draft.prompt.length > MAX_PROMPT_CHARS) {
            return ScheduleMutationResult.Rejected(
                "prompt is too long: ${draft.prompt.length} > $MAX_PROMPT_CHARS"
            )
        }

        val target = resolveAssistant(draft.targetAssistantId)
            ?: return ScheduleMutationResult.Rejected(
                "unknown target assistant: ${draft.targetAssistantId}"
            )
        if (!target.spawnable) {
            return ScheduleMutationResult.Rejected(
                "target assistant is not spawnable: ${draft.targetAssistantId}"
            )
        }

        // The zone is dereferenced at fire time (`ZoneId.of(row.timeZoneId)` in [claimDue]); an
        // unparseable id must be a Rejected create here, not a DateTimeException thrown on EVERY
        // future fire after the row is already persisted.
        if (!isValidZoneId(draft.timeZoneId)) {
            return ScheduleMutationResult.Rejected("invalid timeZoneId: ${draft.timeZoneId}")
        }

        if (draft.kind == ScheduleKind.RECURRING) {
            val spec = parseRecurrenceSpec(draft.recurrenceSpec)
                ?: return ScheduleMutationResult.Rejected(
                    "recurring schedule requires a valid recurrenceSpec"
                )
            val intervalMillis = spec.intervalMillis()
            if (intervalMillis < MIN_RECURRENCE_INTERVAL_MILLIS) {
                return ScheduleMutationResult.Rejected(
                    "recurring interval $intervalMillis ms is below the minimum $MIN_RECURRENCE_INTERVAL_MILLIS ms"
                )
            }
            // A DAYS recurrence's optional `timeOfDay` is parsed with `LocalTime.parse` at fire time
            // (Recurrence.nextDailyOccurrence); a bad "HH:mm" must be rejected upfront, same reason.
            val timeOfDay = spec.timeOfDay
            if (spec.unit == RecurrenceUnit.DAYS && timeOfDay != null && !isValidLocalTime(timeOfDay)) {
                return ScheduleMutationResult.Rejected("invalid daily timeOfDay: $timeOfDay")
            }
        }

        // Caps count only ENABLED schedules; a fired one-shot (disabled) no longer occupies quota.
        val enabledHere = dao.listByConversation(conversationId.toString()).count { it.enabled }
        if (enabledHere >= MAX_ACTIVE_PER_CONVERSATION) {
            return ScheduleMutationResult.Rejected(
                "per-conversation active schedule cap reached ($MAX_ACTIVE_PER_CONVERSATION)"
            )
        }
        if (dao.countEnabledByOwner(owner.name) >= MAX_ACTIVE_PER_USER) {
            return ScheduleMutationResult.Rejected(
                "per-${owner.name.lowercase()} active schedule cap reached ($MAX_ACTIVE_PER_USER)"
            )
        }

        val timestamp = now()
        val entity = TaskScheduleEntity(
            id = Uuid.random().toString(),
            conversationId = conversationId.toString(),
            targetAssistantId = draft.targetAssistantId.toString(),
            prompt = draft.prompt,
            owner = owner.name,
            kind = draft.kind.name,
            recurrenceSpec = if (draft.kind == ScheduleKind.RECURRING) draft.recurrenceSpec else null,
            timeZoneId = draft.timeZoneId,
            firstFireAt = draft.firstFireAt,
            nextFireAt = draft.firstFireAt,
            enabled = true,
            misfirePolicy = draft.misfirePolicy.name,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        dao.insert(entity)
        return ScheduleMutationResult.Accepted(entity.toSnapshot())
    }

    /** Schedules on [conversationId], in presentation order (next_fire_at ascending). */
    suspend fun list(conversationId: Uuid): List<ScheduleSnapshot> = transactions.inTransaction {
        dao.listByConversation(conversationId.toString()).map { it.toSnapshot() }
    }

    /**
     * Delete the schedule [id] iff it belongs to [conversationId]. A row owned by a different
     * conversation (or absent entirely) REJECTS — the scope check is what stops an agent (or UI)
     * bound to one conversation from reaching into another's schedules.
     */
    suspend fun delete(conversationId: Uuid, id: Uuid): ScheduleMutationResult {
        val result = transactions.inTransaction {
            val row = dao.getById(id.toString())
                ?.takeIf { it.conversationId == conversationId.toString() }
                ?: return@inTransaction ScheduleMutationResult.Rejected("schedule not found: $id")
            val snapshot = row.toSnapshot()
            dao.deleteById(id.toString())
            ScheduleMutationResult.Accepted(snapshot)
        }
        // Cancel the deleted schedule's pending fire AFTER the row is gone, so a fire can never be
        // re-armed for a schedule that no longer exists.
        if (result is ScheduleMutationResult.Accepted) {
            onScheduleDeleted(id)
        }
        return result
    }

    /**
     * Atomically claim schedule [scheduleId]'s due window at [now]. ONE transaction, validate before
     * write: returns null unless the row exists AND is `enabled` AND has no in-flight run AND is due
     * (`nextFireAt <= now`). On a win it mints a fresh `runningTaskRunId`, stamps `lastFiredAt`, and
     * either DISABLES a one-shot or ADVANCES a recurring schedule's `nextFireAt` to the first
     * occurrence strictly after [now] (coalescing every window missed while the process was dead into
     * exactly that one fire — never N catch-up runs). The win is reported DIRECTLY: a non-null
     * [ScheduleClaim] means this caller won, so a second concurrent claim for the same window — now
     * seeing a non-null `runningTaskRunId` — loses with null. Mirrors `TaskRunRepository.claimResume`.
     */
    suspend fun claimDue(scheduleId: Uuid, now: Long): ScheduleClaim? = transactions.inTransaction {
        val row = dao.getById(scheduleId.toString()) ?: return@inTransaction null
        if (!row.enabled || row.runningTaskRunId != null || row.nextFireAt > now) {
            return@inTransaction null
        }

        val advancedNextFire: Long? = when (ScheduleKind.valueOf(row.kind)) {
            ScheduleKind.ONE_SHOT -> null // a one-shot fires once: disable after this claim.
            ScheduleKind.RECURRING -> {
                // A row that passed the create gate always carries a valid recurrence_spec; if it is
                // somehow absent we cannot advance, so we disable rather than spin in place.
                parseRecurrenceSpec(row.recurrenceSpec)?.let { spec ->
                    Recurrence.nextOccurrenceAfter(
                        spec = spec,
                        firstFireAt = row.firstFireAt,
                        lastFiredAt = row.lastFiredAt,
                        zone = ZoneId.of(row.timeZoneId),
                        now = now,
                    )
                }
            }
        }

        val runId = Uuid.random()
        val claimed = row.copy(
            runningTaskRunId = runId.toString(),
            lastFiredAt = now,
            nextFireAt = advancedNextFire ?: row.nextFireAt,
            enabled = advancedNextFire != null,
            updatedAt = now,
        )
        dao.update(claimed)
        ScheduleClaim(runId = runId, snapshot = claimed.toSnapshot())
    }

    /**
     * Mark the fire identified by [runId] finished: clear `running_task_run_id` so the schedule is no
     * longer pinned in-flight and record [terminalTaskRunId] as `last_task_run_id`. Abort-safe — an
     * absent row (the conversation was deleted mid-run) or a stale [runId] (the schedule was already
     * re-claimed) is a no-op, never an exception. One transaction.
     */
    suspend fun finishRun(scheduleId: Uuid, runId: Uuid, terminalTaskRunId: Uuid) {
        transactions.inTransaction<Unit> {
            val row = dao.getById(scheduleId.toString()) ?: return@inTransaction
            // Only the worker that owns the in-flight marker may clear it; a stale finisher (the row
            // was re-claimed under a new runId) must not stomp the live claim.
            if (row.runningTaskRunId != runId.toString()) return@inTransaction
            dao.update(
                row.copy(
                    runningTaskRunId = null,
                    lastTaskRunId = terminalTaskRunId.toString(),
                    updatedAt = now(),
                )
            )
        }
    }

    /**
     * Clear schedule [scheduleId]'s `running_task_run_id` unconditionally (SPEC.md M6 / task T11):
     * the startup rescheduler calls this once it has decided the marker is an ORPHAN — a fire whose
     * run the recovery pass folded to `Interrupted`, or whose run no longer exists. Unlike
     * [finishRun] there is no `runId` guard, because the deciding caller already knows the in-flight
     * run is dead and there is no live claimant to protect. Abort-safe: an absent row (the
     * conversation was deleted) or an already-cleared marker is a no-op. One transaction.
     */
    suspend fun clearOrphanRunning(scheduleId: Uuid) {
        transactions.inTransaction<Unit> {
            val row = dao.getById(scheduleId.toString()) ?: return@inTransaction
            if (row.runningTaskRunId == null) return@inTransaction
            dao.update(row.copy(runningTaskRunId = null, updatedAt = now()))
        }
    }

    /** Overdue enabled schedules (`enabled AND next_fire_at <= ` [now]) for the startup rescheduler. */
    suspend fun listOverdueEnabled(now: Long): List<ScheduleSnapshot> = transactions.inTransaction {
        dao.listOverdueEnabled(now).map { it.toSnapshot() }
    }

    /**
     * Enabled schedules carrying an in-flight marker (`running_task_run_id IS NOT NULL`) for the
     * startup rescheduler. A recurring row claimed-but-not-finished across a process kill has its
     * `next_fire_at` already advanced to the future, so it is NOT in [listOverdueEnabled]; the
     * rescheduler reads it here to clear the orphan marker and re-arm its next future fire.
     */
    suspend fun listEnabledRunning(): List<ScheduleSnapshot> = transactions.inTransaction {
        dao.listEnabledRunning().map { it.toSnapshot() }
    }

    // --- gate helpers (transaction-internal) ----------------------------------------------------

    private fun parseRecurrenceSpec(raw: String?): RecurrenceSpec? {
        if (raw.isNullOrBlank()) return null
        // A malformed spec (bad JSON, every < 1) is a rejection, not a crash — the create path must
        // stay abort-safe, so a parse failure folds into the same Rejected the gate returns.
        return runCatching { json.decodeFromString<RecurrenceSpec>(raw) }.getOrNull()
    }

    private fun isValidZoneId(id: String): Boolean =
        runCatching { ZoneId.of(id) }.isSuccess

    private fun isValidLocalTime(value: String): Boolean =
        runCatching { LocalTime.parse(value) }.isSuccess

    private fun RecurrenceSpec.intervalMillis(): Long = when (unit) {
        RecurrenceUnit.MINUTES -> every.toLong() * MILLIS_PER_MINUTE
        RecurrenceUnit.HOURS -> every.toLong() * MILLIS_PER_HOUR
        RecurrenceUnit.DAYS -> every.toLong() * MILLIS_PER_DAY
    }

    private fun TaskScheduleEntity.toSnapshot(): ScheduleSnapshot = ScheduleSnapshot(
        id = Uuid.parse(id),
        targetAssistantId = Uuid.parse(targetAssistantId),
        prompt = prompt,
        owner = ScheduleOwner.valueOf(owner),
        kind = ScheduleKind.valueOf(kind),
        firstFireAt = firstFireAt,
        nextFireAt = nextFireAt,
        timeZoneId = timeZoneId,
        recurrenceSpec = recurrenceSpec,
        misfirePolicy = MisfirePolicy.valueOf(misfirePolicy),
        enabled = enabled,
        lastFiredAt = lastFiredAt,
        lastTaskRunId = lastTaskRunId?.let { Uuid.parse(it) },
        runningTaskRunId = runningTaskRunId?.let { Uuid.parse(it) },
    )

    companion object {
        /** Max enabled schedules per conversation (spec assumption 3). */
        const val MAX_ACTIVE_PER_CONVERSATION: Int = 20

        /** Max enabled schedules per owner class (spec assumption 3/4). */
        const val MAX_ACTIVE_PER_USER: Int = 100

        /**
         * Minimum recurring interval (spec assumption 3): 15 minutes, aligned with WorkManager's own
         * minimum periodic interval — promising a tighter cadence than the transport can honor (and
         * the battery drain it implies) is forbidden.
         */
        const val MIN_RECURRENCE_INTERVAL_MILLIS: Long = 15L * 60 * 1000

        /** Maximum schedule prompt length (spec assumption 3). */
        const val MAX_PROMPT_CHARS: Int = 8000

        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
        private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
    }
}
