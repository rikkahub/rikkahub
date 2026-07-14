package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity

@Dao
interface ManagedFileDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: ManagedFileEntity)

    @Update
    suspend fun update(file: ManagedFileEntity)

    @Query("SELECT * FROM managed_files WHERE id = :id")
    suspend fun getById(id: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE relative_path = :relativePath")
    suspend fun getByPath(relativePath: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE folder = :folder AND (deleted_at IS NULL) ORDER BY created_at DESC")
    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>>

    @Query("SELECT * FROM managed_files WHERE deleted_at IS NULL")
    suspend fun getAll(): List<ManagedFileEntity>

    @Query(
        """
        SELECT * FROM managed_files
        WHERE upload_status IN (:statuses)
          AND deleted_at IS NULL
        ORDER BY updated_at ASC
        LIMIT :limit
        """
    )
    suspend fun listByStatuses(statuses: List<String>, limit: Int): List<ManagedFileEntity>

    @Query("DELETE FROM managed_files WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM managed_files WHERE relative_path = :relativePath")
    suspend fun deleteByPath(relativePath: String): Int

    @Query("DELETE FROM managed_files WHERE folder = :folder")
    suspend fun deleteByFolder(folder: String): Int
}
