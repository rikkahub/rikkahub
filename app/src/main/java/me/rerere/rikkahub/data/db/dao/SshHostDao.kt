package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.SshHostEntity

@Dao
interface SshHostDao {
    @Query("SELECT * FROM ssh_hosts ORDER BY name ASC")
    suspend fun getAll(): List<SshHostEntity>

    @Query("SELECT * FROM ssh_hosts WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: SshHostEntity)

    @Delete
    suspend fun delete(host: SshHostEntity)

    @Query("DELETE FROM ssh_hosts WHERE name = :name")
    suspend fun deleteByName(name: String)
}
