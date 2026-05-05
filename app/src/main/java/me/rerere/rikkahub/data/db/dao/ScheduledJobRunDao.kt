package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity

@Dao
interface ScheduledJobRunDao {
    @Query("SELECT * FROM scheduled_job_runs WHERE jobId = :jobId ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun getRecent(jobId: String, limit: Int): List<ScheduledJobRunEntity>

    @Query("SELECT * FROM scheduled_job_runs WHERE finishedAtMs IS NULL AND startedAtMs < :stalenessMs")
    suspend fun getStranded(stalenessMs: Long): List<ScheduledJobRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ScheduledJobRunEntity)

    @Update
    suspend fun update(row: ScheduledJobRunEntity)

    /**
     * Trim history for a single job to the [keep] most-recent rows (by startedAtMs).
     * Runs at the end of every fire so growth stays bounded.
     */
    @Query("DELETE FROM scheduled_job_runs WHERE jobId = :jobId AND id NOT IN " +
           "(SELECT id FROM scheduled_job_runs WHERE jobId = :jobId ORDER BY startedAtMs DESC LIMIT :keep)")
    suspend fun trim(jobId: String, keep: Int)

    @Query("DELETE FROM scheduled_job_runs WHERE jobId = :jobId")
    suspend fun deleteAllForJob(jobId: String)
}
