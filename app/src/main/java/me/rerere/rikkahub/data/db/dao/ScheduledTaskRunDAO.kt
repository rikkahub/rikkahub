package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunEntity

@Dao
interface ScheduledTaskRunDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: ScheduledTaskRunEntity)

    @Update
    suspend fun update(run: ScheduledTaskRunEntity)

    @Query("SELECT * FROM scheduled_task_run WHERE id = :id LIMIT 1")
    fun getRunFlow(id: String): Flow<ScheduledTaskRunEntity?>

    @Query("SELECT * FROM scheduled_task_run WHERE id = :id LIMIT 1")
    suspend fun getRunById(id: String): ScheduledTaskRunEntity?

    @Query("DELETE FROM scheduled_task_run WHERE id = :id")
    suspend fun deleteRunById(id: String)

    @Query(
        "SELECT * FROM scheduled_task_run " +
            "WHERE status IN ('SUCCESS', 'FAILED') " +
            "ORDER BY started_at DESC " +
            "LIMIT :limit"
    )
    fun getRecentFinishedRuns(limit: Int): Flow<List<ScheduledTaskRunEntity>>

    @Query("DELETE FROM scheduled_task_run WHERE status IN ('SUCCESS', 'FAILED')")
    suspend fun deleteAllFinishedRuns()

    @Query(
        "DELETE FROM scheduled_task_run " +
            "WHERE task_id = :taskId AND id IN (" +
            "SELECT id FROM scheduled_task_run WHERE task_id = :taskId " +
            "ORDER BY started_at DESC LIMIT -1 OFFSET :keep" +
            ")"
    )
    suspend fun pruneTaskRuns(taskId: String, keep: Int)
}
