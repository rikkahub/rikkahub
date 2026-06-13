package me.rerere.rikkahub.data.ai.task

import android.util.Log
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.TaskRunRepository

/**
 * The startup-recovery composition root (SPEC.md M6, Success Criterion #4). On a cold start — the
 * only moment a process-death scan is meaningful — the Application invokes [runStartupRecovery],
 * which performs the two lifecycle-hygiene passes the production path was previously missing:
 *
 *  1. **Interrupt scan.** Every task row that still claims to be in flight
 *     (`TaskRunStateTag.ACTIVE`) is folded to `Interrupted` via
 *     [TaskRunRepository.recoverInterruptedRuns]. This is a pure tag rewrite: NO child is re-run
 *     and NO persisted event is replayed (decisions #1/#3). An already-`Interrupted` row is left
 *     alone, so a second cold start with no resume in between is a no-op (idempotent).
 *  2. **Retention sweep.** Terminal task runs and Completed/Deleted board items beyond the 30-day /
 *     newest-200-per-conversation window are deleted (SPEC.md "Unbounded board" Failure-mode), via
 *     [TaskRunRepository.sweepRetention] and [TaskBoardRepository.sweepRetention].
 *
 * Why the CONCRETE [TaskRunRepository] and not the narrow `TaskRunStore` seam: the recovery and
 * retention methods are lifecycle operations the coordinator never drives, so they intentionally
 * do NOT live on `TaskRunStore` (that interface is exactly the coordinator's surface, no wider).
 * The previous DI binding exposed the repository ONLY as `TaskRunStore`, which is why
 * `recoverInterruptedRuns()` was unreachable at runtime; binding the concrete type here is the
 * fix.
 *
 * Orphan board-claim release (decision #5) does not live here: subagent claims are owned by the
 * live execution-handle id, and the spawn path itself releases every claim a handle still holds
 * the moment that handle dies (success, failure, or cancellation — see `buildSpawnTool`'s
 * `releaseOrphanedClaims`). The one path in-process release cannot reach is process death, where
 * the handle, its registry, and the releasing coroutine all die together; for that the claim
 * lease backstop ([TaskBoardRepository.DEFAULT_CLAIM_LEASE_MILLIS]) bounds the stale claim's
 * lifetime — a startup scan cannot tell a dead handle's claim from a live one's by owner id
 * alone, so the lease, not a blanket release, is the correct cold-start story.
 */
class TaskRecoveryRunner(
    private val taskRuns: TaskRunRepository,
    private val board: TaskBoardRepository,
) {

    /**
     * Run the cold-start recovery + retention passes once. Returns a [Result] summary (recovered
     * row count + swept counts) so a caller/test can assert the effect; failures in either pass are
     * caught and logged rather than crashing app startup — a recovery hiccup must never block the
     * UI from coming up.
     */
    suspend fun runStartupRecovery(
        now: Long = System.currentTimeMillis(),
        retentionMaxAgeMillis: Long = TaskRunRepository.DEFAULT_RETENTION_MAX_AGE_MILLIS,
        retentionKeepNewestPerConversation: Int = TaskRunRepository.DEFAULT_RETENTION_KEEP_NEWEST,
    ): Result {
        val recovered = runCatching { taskRuns.recoverInterruptedRuns() }
            .onFailure { Log.e(TAG, "interrupt scan failed", it) }
            .getOrDefault(emptyList())

        val sweptRuns = runCatching {
            taskRuns.sweepRetention(
                now = now,
                maxAgeMillis = retentionMaxAgeMillis,
                keepNewestPerConversation = retentionKeepNewestPerConversation,
            )
        }.onFailure { Log.e(TAG, "task-run retention sweep failed", it) }.getOrDefault(0)

        val sweptItems = runCatching {
            board.sweepRetention(
                now = now,
                maxAgeMillis = retentionMaxAgeMillis,
                keepNewestPerConversation = retentionKeepNewestPerConversation,
            )
        }.onFailure { Log.e(TAG, "board retention sweep failed", it) }.getOrDefault(0)

        Log.i(TAG, "startup recovery: ${recovered.size} interrupted, $sweptRuns runs + $sweptItems items swept")
        return Result(recoveredRuns = recovered.size, sweptRuns = sweptRuns, sweptItems = sweptItems)
    }

    /** What one [runStartupRecovery] pass did, for logging and tests. */
    data class Result(
        val recoveredRuns: Int,
        val sweptRuns: Int,
        val sweptItems: Int,
    )

    private companion object {
        const val TAG = "TaskRecoveryRunner"
    }
}
