package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity

@Dao
interface ScheduledJobDao {
    @Query("SELECT * FROM scheduled_jobs ORDER BY createdAtMs DESC")
    suspend fun getAll(): List<ScheduledJobEntity>

    @Query("SELECT * FROM scheduled_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScheduledJobEntity?

    @Query("SELECT * FROM scheduled_jobs WHERE enabled = 1")
    suspend fun getEnabled(): List<ScheduledJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ScheduledJobEntity)

    @Update
    suspend fun update(job: ScheduledJobEntity)

    @Delete
    suspend fun delete(job: ScheduledJobEntity)

    @Query("DELETE FROM scheduled_jobs WHERE id = :id")
    suspend fun deleteById(id: String)
}
