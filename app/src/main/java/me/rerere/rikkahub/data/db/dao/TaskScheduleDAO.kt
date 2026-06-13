package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.TaskScheduleEntity

/**
 * Row-level access to persisted task schedules (SPEC.md M2). This DAO is deliberately dumb: the
 * atomic claim-and-advance and finish-run transactions, every legality gate, and the
 * coalesce-missed-windows advance all live in `TaskScheduleRepository` (the single enforcement
 * point UI and tools share), composed from these primitive row reads/writes — never as a clever
 * multi-row UPDATE here.
 */
@Dao
interface TaskScheduleDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(schedule: TaskScheduleEntity)

    @Update
    suspend fun update(schedule: TaskScheduleEntity)

    @Delete
    suspend fun delete(schedule: TaskScheduleEntity)

    @Query("SELECT * FROM task_schedules WHERE id = :id")
    suspend fun getById(id: String): TaskScheduleEntity?

    @Query("DELETE FROM task_schedules WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM task_schedules WHERE conversation_id = :conversationId ORDER BY next_fire_at ASC")
    suspend fun listByConversation(conversationId: String): List<TaskScheduleEntity>

    /**
     * Per-owner active-schedule cap key (SPEC.md M3): the count of ENABLED schedules owned by
     * [owner] across ALL conversations. The cap is computed per owner class so an agent cannot
     * starve the user's quota (spec assumption 4); the per-conversation cap is derived from
     * [listByConversation] instead, so no separate per-conversation count query is needed.
     */
    @Query("SELECT COUNT(*) FROM task_schedules WHERE owner = :owner AND enabled = 1")
    suspend fun countEnabledByOwner(owner: String): Int

    /**
     * Startup-rescheduler key (SPEC.md M6): every enabled schedule whose next fire is at or before
     * [now], i.e. overdue and still armed. The single-claim invariant (one worker wins the window)
     * is enforced by the repository's `claimDue` transaction, not by this read.
     */
    @Query("SELECT * FROM task_schedules WHERE enabled = 1 AND next_fire_at <= :now ORDER BY next_fire_at ASC")
    suspend fun listOverdueEnabled(now: Long): List<TaskScheduleEntity>

    /**
     * Startup-rescheduler key (SPEC.md M6): every enabled schedule carrying an in-flight marker
     * (`running_task_run_id IS NOT NULL`). `claimDue` advances a recurring row's `next_fire_at` to
     * the FUTURE before the run starts, so a row claimed-but-not-finished across a process kill is
     * NOT overdue — [listOverdueEnabled] never sees it. The rescheduler reads it here too so its
     * orphan marker is cleared and its next future fire is re-armed, otherwise the schedule silently
     * stops firing (no pending work + a stale marker that blocks every future claim).
     */
    @Query("SELECT * FROM task_schedules WHERE enabled = 1 AND running_task_run_id IS NOT NULL ORDER BY next_fire_at ASC")
    suspend fun listEnabledRunning(): List<TaskScheduleEntity>

    /** Cascade with explicit conversation cleanup (SPEC.md M6); no Room foreign-key cascade exists. */
    @Query("DELETE FROM task_schedules WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int
}
