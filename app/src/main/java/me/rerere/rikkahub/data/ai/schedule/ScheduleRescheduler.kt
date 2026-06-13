package me.rerere.rikkahub.data.ai.schedule

import android.util.Log
import kotlin.uuid.Uuid

/**
 * The cold-start rescheduler pass (SPEC.md M6 / task T11): [RikkaHubApp] invokes [rescheduleOverdue]
 * once after `recoverTasks()` so the firing transport is reconciled with the persisted schedule rows
 * the moment the process comes up. WorkManager already persists enqueued work across reboot, so this
 * is the belt-and-suspenders catch-up (spec Open Question 3): it guarantees an overdue schedule that
 * lost its pending request — or was pinned "running" by a process kill — fires again.
 *
 * It is pure policy over narrow injected seams (DIP), so the ordering is JVM-unit-testable with
 * fakes and the rescheduler keeps no compile-time edge to Room or WorkManager — exactly as
 * [ScheduleFireRunner] is extracted from the worker. Two hygiene passes, per row:
 *
 *  1. **Orphan-running clear.** A schedule whose `running_task_run_id` points at a run the recovery
 *     pass already folded to `Interrupted` (or a run that no longer exists) is NOT actually running:
 *     the process that owned that fire is dead. Left as-is, the claim — which filters on
 *     `running_task_run_id == null` — would refuse every future fire, pinning the schedule "running"
 *     forever. So an orphan marker is cleared first ([isRunOrphan] decides, [clearOrphanRunning]
 *     clears). A LIVE in-flight run's marker is left untouched.
 *  2. **Re-enqueue.** Every overdue enabled schedule is re-enqueued at its `next_fire_at` via the
 *     unique-work [enqueue] seam (the unique name dedups against any request WorkManager still holds),
 *     so the missed window fires on the next opportunity. Recurring missed occurrences coalesce into
 *     ONE fire inside `claimDue` — the rescheduler only re-arms the transport, it does not advance.
 *
 * The reconciled set is the UNION of two reads, deduped by id:
 *  - **overdue enabled** (`enabled AND next_fire_at <= now`): a fire missed while the process was dead.
 *  - **enabled with a running marker** (`enabled AND running_task_run_id IS NOT NULL`): a schedule
 *    that was CLAIMED but whose worker died before it re-enqueued the next occurrence. `claimDue`
 *    advances a recurring row's `next_fire_at` to the FUTURE before the run starts, so after a process
 *    kill such a row is NOT overdue — the overdue read alone never sees it, it has no pending
 *    WorkManager work, and its stale running marker would block every future claim. Folding it into
 *    the union re-arms the transport (at its future `next_fire_at`) and clears the orphan marker, so a
 *    recurring schedule cannot silently stop firing across a claim/finish process death.
 *
 * @param listOverdueEnabled reads the overdue enabled schedule rows (`enabled AND next_fire_at <= now`).
 * @param listEnabledRunning reads enabled rows carrying a non-null `running_task_run_id`.
 * @param isRunOrphan true iff the given run id points at an `Interrupted`/missing task run — i.e. a
 *   killed fire, not a live one.
 * @param clearOrphanRunning clears the schedule's `running_task_run_id` (the orphan marker).
 * @param enqueue the [ScheduleEnqueuer] transport seam: re-arm the unique fire for (id, fireAt).
 * @param now wall-clock source for the overdue read; injected for testability.
 */
class ScheduleRescheduler(
    private val listOverdueEnabled: suspend () -> List<me.rerere.ai.runtime.contract.ScheduleSnapshot>,
    private val listEnabledRunning: suspend () -> List<me.rerere.ai.runtime.contract.ScheduleSnapshot>,
    private val isRunOrphan: suspend (Uuid) -> Boolean,
    private val clearOrphanRunning: suspend (Uuid) -> Unit,
    private val enqueue: (Uuid, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Reconcile the firing transport with the persisted rows: for the union (deduped by id) of
     * overdue-enabled and enabled-with-a-running-marker schedules, clear any orphan running marker
     * then re-enqueue at the row's `next_fire_at`. Idempotent — a second pass with no new orphan and
     * no consumed fire re-arms the same unique work (REPLACE) and clears nothing. A row appearing in
     * both reads is reconciled exactly once.
     */
    suspend fun rescheduleOverdue() {
        val reconciled = LinkedHashMap<Uuid, me.rerere.ai.runtime.contract.ScheduleSnapshot>()
        for (schedule in listOverdueEnabled()) reconciled.putIfAbsent(schedule.id, schedule)
        for (schedule in listEnabledRunning()) reconciled.putIfAbsent(schedule.id, schedule)
        for (schedule in reconciled.values) {
            val runningId = schedule.runningTaskRunId
            if (runningId != null && isRunOrphan(runningId)) {
                clearOrphanRunning(schedule.id)
            }
            enqueue(schedule.id, schedule.nextFireAt)
        }
        Log.i(TAG, "rescheduleOverdue: re-enqueued ${reconciled.size} schedules")
    }

    private companion object {
        const val TAG = "ScheduleRescheduler"
    }
}
