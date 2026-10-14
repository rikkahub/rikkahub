package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity

@Dao
interface TranslationHistoryDAO {
    @Query("SELECT * FROM translation_history ORDER BY create_at DESC")
    fun pagingAll(): PagingSource<Int, TranslationHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslationHistoryEntity)

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM translation_history")
    suspend fun deleteAll()

    // 只留最新的 keep 条
    @Query("DELETE FROM translation_history WHERE id NOT IN (SELECT id FROM translation_history ORDER BY create_at DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
