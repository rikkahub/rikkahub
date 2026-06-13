package me.rerere.rikkahub.data.ai.task

import android.util.Log

/**
 * The cold-start lifecycle orchestrator (SPEC.md M6 / SC#4 + task T11). [me.rerere.rikkahub.RikkaHubApp]
 * runs [run] in ONE coroutine so the two passes execute STRICTLY in order:
 *
 *  1. [recover] — the interrupt-scan: fold every task row left in flight by a process kill to
 *     `Interrupted` (and sweep retention). See [TaskRecoveryRunner.runStartupRecovery].
 *  2. [reschedule] — the schedule rescheduler: clear any ORPHAN `running_task_run_id` marker and
 *     re-enqueue overdue schedules. See [me.rerere.rikkahub.data.ai.schedule.ScheduleRescheduler].
 *
 * Why the order is load-bearing, not cosmetic: the rescheduler's orphan predicate decides a marker
 * is orphaned by reading the run's state — a run is an orphan iff it is missing or `Interrupted`.
 * That `Interrupted` tag is written by pass 1. The two passes previously ran in two independent
 * `AppScope.launch` coroutines with NO happens-before edge, so if rescheduling won the race it read
 * a killed run that recovery had not yet folded to `Interrupted`, saw it as still-running, left the
 * stale marker, and `claimDue` (which refuses any row with a non-null `running_task_run_id`) then
 * pinned the recurring schedule "running" — it never fired again until a later startup happened to
 * win the race the other way. Awaiting [recover] before [reschedule] in one coroutine removes the
 * race: pass 2 always observes pass 1's writes.
 *
 * Each pass swallows and logs its own failures (so a recovery hiccup never blocks the UI). This
 * orchestrator only sequences them; it adds no error handling of its own beyond the catch the caller
 * wraps it in.
 */
class StartupRecoveryRunner(
    private val recover: suspend () -> Unit,
    private val reschedule: suspend () -> Unit,
) {
    /**
     * Run recovery to completion, THEN run rescheduling. The suspension point on [recover] is the
     * happens-before edge the rescheduler's orphan predicate depends on.
     */
    suspend fun run() {
        recover()
        reschedule()
        Log.i(TAG, "startup recovery + reschedule complete")
    }

    private companion object {
        const val TAG = "StartupRecoveryRunner"
    }
}
