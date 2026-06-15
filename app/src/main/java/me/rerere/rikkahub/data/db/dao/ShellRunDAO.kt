package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ShellRunEntity

/**
 * Row-level access to the persisted shell-run state (issue #291). Lifecycle policy (the legal state
 * machine, the at-most-once terminal write, cold-start recovery selection) lives in
 * [me.rerere.rikkahub.data.ai.shellrun.ShellRunStore], not in queries here — these are exactly the
 * primitives that layer composes, no wider, mirroring [AgentEventDAO].
 *
 * Every state-flip update is GUARDED by its legal predecessor status in the `WHERE` clause, so a
 * stale/duplicate flip matches zero rows instead of corrupting the lifecycle, and returns the
 * affected-row count so the store knows whether THIS caller won the transition.
 */
@Dao
interface ShellRunDAO {
    /** Insert a fresh run. No conflict strategy needed — [ShellRunEntity.taskId] is freshly random. */
    @Insert
    suspend fun insert(entity: ShellRunEntity)

    @Query("SELECT * FROM shell_runs WHERE task_id = :taskId")
    suspend fun getById(taskId: String): ShellRunEntity?

    /** STARTED -> FOREGROUND_WAITING once the handle is held; only a STARTED row flips. */
    @Query(
        "UPDATE shell_runs SET status = 'FOREGROUND_WAITING', started_at = :startedAt, pid_meta = :pidMeta " +
            "WHERE task_id = :taskId AND status = 'STARTED'"
    )
    suspend fun markForegroundWaiting(taskId: String, startedAt: Long, pidMeta: String?): Int

    /**
     * FOREGROUND_WAITING -> DETACHED when `detachAfterSeconds` (or a user stop) wins before exit. Only
     * a non-terminal, not-yet-detached row flips (STARTED is included so a stop that races the
     * foreground-wait flip still detaches).
     */
    @Query(
        "UPDATE shell_runs SET status = 'DETACHED', detached_at = :detachedAt, pid_meta = :pidMeta " +
            "WHERE task_id = :taskId AND status IN ('STARTED', 'FOREGROUND_WAITING')"
    )
    suspend fun markDetached(taskId: String, detachedAt: Long, pidMeta: String?): Int

    /** DETACHED -> BACKGROUND_RUNNING once the detached awaiter is running; only a DETACHED row flips. */
    @Query(
        "UPDATE shell_runs SET status = 'BACKGROUND_RUNNING' WHERE task_id = :taskId AND status = 'DETACHED'"
    )
    suspend fun markBackgroundRunning(taskId: String): Int

    /**
     * The SINGLE_TERMINAL conditional CAS: flip a still-running row to a terminal [status], stamping
     * the terminal columns. The `status IN ('STARTED','FOREGROUND_WAITING','DETACHED',
     * 'BACKGROUND_RUNNING')` predicate is the claim — two callers racing the same row (the detached
     * awaiter winning a real exit vs a cold-start recovery scan) serialize through the transaction,
     * and only the one that observed a non-terminal status writes; the loser matches zero rows. The
     * returned affected-row count is 1 for the winner, 0 for the loser, mirroring
     * [AgentEventDAO.markConsumed].
     */
    @Query(
        "UPDATE shell_runs SET status = :status, exit_code = :exitCode, byte_count = :byteCount, " +
            "kill_reason = :killReason, completed_at = :completedAt " +
            "WHERE task_id = :taskId AND status IN " +
            "('STARTED', 'FOREGROUND_WAITING', 'DETACHED', 'BACKGROUND_RUNNING')"
    )
    suspend fun markTerminalIfRunning(
        taskId: String,
        status: String,
        exitCode: Int?,
        byteCount: Long,
        killReason: String?,
        completedAt: Long,
    ): Int

    /**
     * Every row still claiming to be in flight — the cold-start recovery scan. A process kill cannot
     * leave any other status, so these are exactly the rows recovery folds to
     * INTERRUPTED_PROCESS_DEATH.
     */
    @Query(
        "SELECT * FROM shell_runs WHERE status IN " +
            "('STARTED', 'FOREGROUND_WAITING', 'DETACHED', 'BACKGROUND_RUNNING')"
    )
    suspend fun runningRows(): List<ShellRunEntity>

    /** Cleanup hook for a deleted conversation (no FK cascade, mirroring [AgentEventDAO]). */
    @Query("DELETE FROM shell_runs WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int
}
