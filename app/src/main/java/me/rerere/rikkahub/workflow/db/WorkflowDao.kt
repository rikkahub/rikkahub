package me.rerere.rikkahub.workflow.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowDao {

    @Query("SELECT * FROM workflows ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows ORDER BY name COLLATE NOCASE ASC")
    suspend fun listAll(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE enabled = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun listEnabled(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getById(id: String): WorkflowEntity?

    @Query("SELECT * FROM workflows WHERE id = :id")
    fun observeById(id: String): Flow<WorkflowEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowEntity)

    @Update
    suspend fun update(entity: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE workflows SET enabled = :enabled, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAtMs: Long): Int

    @Query("""
        UPDATE workflows
        SET lastRunAtMs = :firedAtMs,
            lastRunStatus = :status,
            lastRunError = :errorMessage,
            runsTodayCount = :runsTodayCount,
            runsTodayDate = :runsTodayDate
        WHERE id = :id
    """)
    suspend fun recordFire(
        id: String,
        firedAtMs: Long,
        status: String,
        errorMessage: String?,
        runsTodayCount: Int,
        runsTodayDate: String,
    ): Int
}
