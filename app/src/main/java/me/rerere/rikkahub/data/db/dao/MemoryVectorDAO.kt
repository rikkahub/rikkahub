package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MemoryVectorEntity

@Dao
interface MemoryVectorDAO {
    @Query("SELECT * FROM memory_vector WHERE memory_id IN (:ids)")
    suspend fun getByMemoryIds(ids: List<Int>): List<MemoryVectorEntity>

    // REPLACE makes a re-embed idempotent: the new vector overwrites any prior row for the same
    // memory_id (1:1 with MemoryEntity), so a content edit never leaves two rows for one memory.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MemoryVectorEntity)

    @Query("DELETE FROM memory_vector WHERE memory_id = :id")
    suspend fun deleteByMemory(id: Int)
}
