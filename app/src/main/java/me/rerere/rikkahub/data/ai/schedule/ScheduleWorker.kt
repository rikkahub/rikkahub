package me.rerere.rikkahub.data.ai.schedule

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.repository.ScheduleClaim
import kotlin.uuid.Uuid

private const val TAG = "ScheduleWorker"

/**
 * The fire policy [ScheduleWorker] delegates to (SPEC.md M5 / task T9), extracted as a plain class so
 * the claim -> run -> finish -> re-enqueue ordering is JVM-unit-testable with fakes — a real
 * [CoroutineWorker] needs a `Context`/`WorkerParameters` and cannot run on the JVM unit path. SoC:
 * this runner names no WorkManager, no Room, no settings store; it depends only on six narrow injected
 * seams bound at the composition root, mirroring how [ScheduledTaskRunner] and
 * [me.rerere.rikkahub.data.repository.TaskScheduleRepository] take injected lookups.
 *
 * The flow (SPEC.md "Worker flow"), driven from a winning claim and NOT from the enqueued payload —
 * the claim is the source of truth (spec assumption 7), so a stale request always claims against the
 * current row:
 *  1. `claimDue(scheduleId, now)`; a `null` claim (already fired / disabled / not due) is an
 *     idempotent no-op — a duplicate worker does nothing.
 *  2. `run(claim, parentConversationId)` reuses [ScheduledTaskRunner] (= `TaskCoordinator.run`); a
 *     missing parent conversation (deleted between enqueue and fire) skips the run.
 *  3. `finishRun(scheduleId, claim.runId, terminalRunId)` clears `running_task_run_id` — run STILL
 *     called even when the run was skipped, so a missing-parent / orphaned fire never pins the
 *     schedule "running" forever.
 *  4. If the post-claim snapshot is still `enabled` (a recurring schedule that advanced), enqueue the
 *     next fire at the advanced `nextFireAt`. One re-enqueue per fire; the unique name dedups.
 *
 * @param claimDue the repository's atomic claim-and-advance; a non-null result means this worker won
 *   the window (the win is carried in the value, never inferred from post-state).
 * @param resolveParentConversation maps a schedule id to its bound parent conversation id, or null if
 *   that conversation was deleted. Threaded into [run] so the spawned task keys on the REAL parent —
 *   never `TaskCoordinator.run`'s `Uuid.random()` default.
 * @param run fires a winning claim against its target and returns the terminal run id (or null if the
 *   target assistant no longer exists). Bound to [ScheduledTaskRunner.run] at the root.
 * @param finishRun clears the in-flight marker and records the terminal run id. Abort-safe in the repo.
 * @param enqueue re-enqueues the next unique fire for a recurring schedule. Bound to
 *   [ScheduleEnqueuer.enqueue] at the root.
 * @param now wall-clock source for the claim's due check; injected for testability.
 */
class ScheduleFireRunner(
    private val claimDue: suspend (Uuid, Long) -> ScheduleClaim?,
    private val resolveParentConversation: suspend (Uuid) -> Uuid?,
    private val run: suspend (ScheduleClaim, Uuid) -> Uuid?,
    private val finishRun: suspend (Uuid, Uuid, Uuid) -> Unit,
    private val enqueue: (Uuid, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Fire schedule [scheduleId] once. Returns true if a window was claimed and processed, false if
     * the claim was a no-op (already fired / disabled / not due) — the worker maps both to
     * `Result.success()`, since neither is a retryable failure.
     */
    suspend fun fire(scheduleId: Uuid): Boolean {
        val claim = claimDue(scheduleId, now()) ?: return false

        // Once claimDue has stamped the in-flight marker, releasing it (finishRun) and re-arming a
        // recurring schedule's next fire (enqueue) MUST happen on EVERY exit path — including when
        // run() throws (e.g. TaskCoordinator.run aborts before a terminal row when the model is
        // missing). Without this finally a thrown run skipped finishRun, leaving the schedule pinned
        // "running" forever; the worker's Result.failure() then hit a retry whose claim was blocked by
        // that stale marker (null), which the worker mapped to success — swallowing the failure. The
        // finally clears the marker and re-enqueues the next occurrence, then the exception propagates
        // so the failure is recorded, not hidden.
        var terminalRunId = claim.runId
        try {
            val parent = resolveParentConversation(scheduleId)
            // A missing parent (conversation deleted between enqueue and fire) skips the run; the
            // claim run id stands in as the terminal id (recording it is harmless, the marker is
            // cleared either way).
            if (parent != null) {
                terminalRunId = run(claim, parent) ?: claim.runId
            }
        } finally {
            finishRun(scheduleId, claim.runId, terminalRunId)
            // A recurring schedule that advanced is still enabled in its post-claim snapshot; re-enqueue
            // the next fire so a thrown run never loses the recurrence. A one-shot (disabled after its
            // claim) is NOT re-enqueued. The unique name ("schedule_$id") dedups, so exactly one pending
            // fire ever exists per schedule.
            if (claim.snapshot.enabled) {
                enqueue(scheduleId, claim.snapshot.nextFireAt)
            }
        }
        return true
    }
}

/**
 * The WorkManager entry point for a scheduled fire (SPEC.md M5 / task T9). Deliberately thin: it
 * reads ONLY the schedule id from its input data ([ScheduleEnqueuer.KEY_SCHEDULE_ID]) — everything
 * else is read from the repository at fire time (spec assumption 7) — and delegates all ordering to
 * the injected [ScheduleFireRunner]. Koin's `worker { }` factory supplies [appContext]/[params] plus
 * the runner; the AndroidX default `WorkManagerInitializer` is removed in the manifest so this Koin
 * factory owns worker construction.
 *
 * A malformed/absent id, or a no-op claim, both resolve to [Result.success]: neither is a transient
 * failure WorkManager should retry. An exception in the fire is logged and the work fails (WorkManager
 * applies its own backoff) rather than silently succeeding.
 */
class ScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
    private val fireRunner: ScheduleFireRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val raw = inputData.getString(ScheduleEnqueuer.KEY_SCHEDULE_ID)
        val scheduleId = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (scheduleId == null) {
            // No id to act on: a request with no/garbled payload can never fire a schedule. Not a
            // retryable failure — succeed and let the request drop.
            Log.w(TAG, "doWork: missing or malformed schedule id in input data, dropping")
            return Result.success()
        }
        return runCatching { fireRunner.fire(scheduleId) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { e ->
                    Log.e(TAG, "doWork: fire failed for schedule $scheduleId", e)
                    Result.failure()
                },
            )
    }
}
