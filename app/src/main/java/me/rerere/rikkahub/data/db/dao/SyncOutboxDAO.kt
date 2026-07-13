package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity

@Dao
interface SyncOutboxDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SyncOutboxEntity)

    @Update
    suspend fun update(entity: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox ORDER BY created_at ASC")
    fun observeAll(): Flow<List<SyncOutboxEntity>>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE next_retry_at <= :now
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun listReady(now: Long, limit: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE mutation_id = :mutationId LIMIT 1")
    suspend fun getById(mutationId: String): SyncOutboxEntity?

    @Query("DELETE FROM sync_outbox WHERE mutation_id = :mutationId")
    suspend fun deleteById(mutationId: String)

    @Query("DELETE FROM sync_outbox")
    suspend fun clearAll()
}
