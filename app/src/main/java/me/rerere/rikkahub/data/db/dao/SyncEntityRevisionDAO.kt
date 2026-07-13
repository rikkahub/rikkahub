package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SyncEntityRevisionEntity

@Dao
interface SyncEntityRevisionDAO {
    @Query(
        """
        SELECT * FROM sync_entity_revision
        WHERE entity_type = :entityType AND entity_id = :entityId
        LIMIT 1
        """
    )
    suspend fun get(entityType: String, entityId: String): SyncEntityRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncEntityRevisionEntity)

    @Query("DELETE FROM sync_entity_revision WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun delete(entityType: String, entityId: String)
}
