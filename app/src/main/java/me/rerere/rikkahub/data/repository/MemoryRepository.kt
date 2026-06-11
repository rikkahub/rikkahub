package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.rikkahub.data.ai.memory.MemoryEmbedderResolver
import me.rerere.rikkahub.data.ai.memory.buildMemoryVectorRow
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryVectorDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryVectorEntity
import me.rerere.rikkahub.data.model.AssistantMemory

/**
 * Memory CRUD + embed-on-write lifecycle for Memory v2 relevance recall (issue #210).
 *
 * Embed-on-write contract (write tool & UI both go through here): a content write and its embedding
 * are split so the content is the source of truth and a network failure never blocks or loses a write:
 *  1. [AppDatabase.withTransaction] writes the content (stamping `updated_at`, and `created_at` on
 *     insert) and deletes any now-stale `memory_vector` row — NO network inside the transaction.
 *  2. embed the content via the resolved embedder OUTSIDE the transaction.
 *  3. on success, upsert the `memory_vector` row (content hash + embedding-space label).
 * If embedding fails or no embedding model is configured, the content row still persists and recall
 * degrades to recency for that memory until it is re-embedded on a later write.
 *
 * @property now wall-clock for the timestamp stamps; injected so writes are deterministic in tests.
 */
class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val database: AppDatabase,
    private val memoryVectorDAO: MemoryVectorDAO,
    private val embedderResolver: MemoryEmbedderResolver,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    /** Recall-path read: memory rows of [assistantId] carrying the timestamps the age render needs. */
    suspend fun getRecalledMemoriesOfAssistant(assistantId: String): List<RecalledMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toRecalledMemory() }
    }

    /** Recall-path read: the stored vector rows for the given memory ids (empty list ⇒ none stored). */
    suspend fun getMemoryVectors(memoryIds: List<Int>): List<MemoryVectorEntity> {
        if (memoryIds.isEmpty()) return emptyList()
        return memoryVectorDAO.getByMemoryIds(memoryIds)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        // (1) content + timestamp; drop any vector that embedded the OLD content. No network in txn.
        val updated = old.copy(content = content, updatedAt = now())
        database.withTransaction {
            memoryDAO.updateMemory(updated)
            memoryVectorDAO.deleteByMemory(id)
        }
        // (2)+(3) re-embed outside the txn; failure leaves no vector ⇒ recency fallback.
        embedAndStore(updated.id, content)
        return AssistantMemory(id = updated.id, content = updated.content)
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val timestamp = now()
        // (1) insert content with created_at == updated_at. No network in txn.
        val newId = database.withTransaction {
            memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = content,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            ).toInt()
        }
        // (2)+(3) embed outside the txn; failure leaves no vector ⇒ recency fallback.
        embedAndStore(newId, content)
        return AssistantMemory(id = newId, content = content)
    }

    suspend fun deleteMemory(id: Int) {
        database.withTransaction {
            memoryDAO.deleteMemory(id)
            memoryVectorDAO.deleteByMemory(id)
        }
    }

    /**
     * Embeds [content] and upserts its `memory_vector` row, OUTSIDE any DB transaction. Best-effort:
     * no embedding model configured ⇒ no row; embed failure ⇒ no row (logged, not propagated). Either
     * way the content row already persists and recall degrades to recency for this memory.
     */
    private suspend fun embedAndStore(memoryId: Int, content: String) {
        val context = embedderResolver.resolve() ?: return
        val row = buildMemoryVectorRow(
            embedder = context.embedder,
            memoryId = memoryId,
            content = content,
            embeddingSpace = context.embeddingSpace,
        ) ?: return
        memoryVectorDAO.upsert(row)
    }
}

private fun MemoryEntity.toRecalledMemory(): RecalledMemory = RecalledMemory(
    id = id,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
