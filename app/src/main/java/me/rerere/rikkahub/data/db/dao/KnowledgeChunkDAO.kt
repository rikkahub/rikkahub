package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity

@Dao
interface KnowledgeChunkDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)

    @Query("SELECT * FROM knowledge_chunk WHERE kb_id = :kbId")
    suspend fun getByKb(kbId: String): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunk WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<KnowledgeChunkEntity>

    @Query("SELECT COUNT(*) FROM knowledge_chunk WHERE kb_id = :kbId")
    suspend fun countByKb(kbId: String): Int

    @Query("DELETE FROM knowledge_chunk WHERE kb_id = :kbId")
    suspend fun deleteByKb(kbId: String)

    @Query("DELETE FROM knowledge_chunk WHERE kb_id = :kbId AND doc_id = :docId")
    suspend fun deleteByDoc(kbId: String, docId: String)
}
