package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SyncStateEntity

@Dao
interface SyncStateDAO {
    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query(
        """
        UPDATE sync_state
        SET change_cursor = :cursor,
            last_success_at = :lastSuccessAt,
            last_error = NULL,
            updated_at = :updatedAt
        WHERE id = 1
        """
    )
    suspend fun updateCursor(cursor: Long, lastSuccessAt: Long, updatedAt: Long)

    @Query(
        """
        UPDATE sync_state
        SET last_error = :error,
            updated_at = :updatedAt
        WHERE id = 1
        """
    )
    suspend fun updateError(error: String?, updatedAt: Long)
}
