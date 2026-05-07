package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity

@Dao
interface ScheduledJobDao {
    @Query("SELECT * FROM scheduled_jobs ORDER BY createdAtMs DESC")
    suspend fun getAll(): List<ScheduledJobEntity>

    @Query("SELECT * FROM scheduled_jobs ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<ScheduledJobEntity>>

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

    @Query("SELECT * FROM scheduled_jobs WHERE " +
           "(:tag IS NULL OR (',' || COALESCE(tags,'') || ',') LIKE '%,' || :tag || ',%') AND " +
           "(:mode IS NULL OR mode = :mode) AND " +
           "(:enabledOrNull IS NULL OR enabled = :enabledOrNull) " +
           "ORDER BY createdAtMs DESC")
    suspend fun listFiltered(tag: String?, mode: String?, enabledOrNull: Boolean?): List<ScheduledJobEntity>
}
