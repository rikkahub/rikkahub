package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SandboxEntity

@Dao
interface SandboxDAO {
    @Query("SELECT * FROM sandbox ORDER BY created_at ASC")
    fun getAllFlow(): Flow<List<SandboxEntity>>

    @Query("SELECT * FROM sandbox WHERE id = :id")
    suspend fun getById(id: String): SandboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SandboxEntity)

    @Query("DELETE FROM sandbox WHERE id = :id")
    suspend fun deleteById(id: String)
}
