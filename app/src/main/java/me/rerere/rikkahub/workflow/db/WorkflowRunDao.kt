package me.rerere.rikkahub.workflow.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WorkflowRunDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WorkflowRunEntity): Long

    @Query("""
        SELECT * FROM workflow_runs
        WHERE workflowId = :workflowId
        ORDER BY firedAtMs DESC
        LIMIT :limit
    """)
    suspend fun lastN(workflowId: String, limit: Int): List<WorkflowRunEntity>

    /**
     * Trim the rows for [workflowId] to the most recent [keep]. Called at the end of every
     * fire to keep history bounded. Implementation: delete every rowId older than the
     * top-[keep] cutoff. Reuses the same idiom as ScheduledJobRunDao.trim().
     */
    @Query("""
        DELETE FROM workflow_runs
        WHERE workflowId = :workflowId
          AND rowId NOT IN (
            SELECT rowId FROM workflow_runs
            WHERE workflowId = :workflowId
            ORDER BY firedAtMs DESC
            LIMIT :keep
          )
    """)
    suspend fun trim(workflowId: String, keep: Int)

    @Query("DELETE FROM workflow_runs WHERE workflowId = :workflowId")
    suspend fun deleteAllFor(workflowId: String): Int

    @Query("SELECT COUNT(*) FROM workflow_runs WHERE workflowId = :workflowId AND firedAtMs >= :sinceMs AND status IN ('SUCCESS', 'FAILED')")
    suspend fun countCountedFiresSince(workflowId: String, sinceMs: Long): Int
}
