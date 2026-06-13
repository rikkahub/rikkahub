package me.rerere.rikkahub.data.ai.schedule

import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * The thin WorkManager seam for schedule firing (SPEC.md M5 / task T8). It owns ONE rule: a schedule
 * id maps to a single unique work chain `schedule_$id`, so re-enqueuing the same schedule REPLACES
 * its pending request rather than stacking a duplicate fire — the unique name is the dedup key.
 *
 * It is deliberately a seam, not logic: it builds a [OneTimeWorkRequest] that carries ONLY the
 * schedule id as input data ([KEY_SCHEDULE_ID]) and reads everything else from the repository at
 * fire time (spec assumption 7 — the claim is the source of truth, so a stale enqueued request
 * always claims against the current row). No in-process timer, no exact alarm: WorkManager's inexact
 * deadline is the v1 transport.
 *
 * @param workManager the process [WorkManager] (Koin-provided; the AndroidX default initializer is
 *   already removed in the manifest so the Koin factory owns worker construction).
 * @param workerClass the worker to run when a schedule fires; injected (not hard-referenced) so this
 *   seam stays a pure transport with no compile-time edge to the worker implementation.
 * @param now wall-clock source for deriving the initial delay; injected for testability.
 */
class ScheduleEnqueuer(
    private val workManager: WorkManager,
    private val workerClass: Class<out ListenableWorker>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Enqueue (or REPLACE) the unique fire for schedule [id] at wall-clock [fireAt]. The initial
     * delay is `fireAt - now`, floored at zero so an already-overdue schedule fires immediately.
     */
    fun enqueue(id: Uuid, fireAt: Long) {
        val delayMillis = (fireAt - now()).coerceAtLeast(0L)
        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_SCHEDULE_ID to id.toString()))
            .build()
        workManager.enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancel the unique fire chain for schedule [id] (e.g. on delete/disable). */
    fun cancel(id: Uuid) {
        workManager.cancelUniqueWork(uniqueName(id))
    }

    companion object {
        /** Input-data key carrying the schedule id to the worker (the only payload it needs). */
        const val KEY_SCHEDULE_ID: String = "schedule_id"

        /** One unique work chain per schedule id, so a re-enqueue replaces rather than stacks. */
        fun uniqueName(id: Uuid): String = "schedule_$id"
    }
}
