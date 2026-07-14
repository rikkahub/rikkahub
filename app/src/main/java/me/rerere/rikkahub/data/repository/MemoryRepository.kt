package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.sync.cloud.MemoryDomainSync
import kotlin.uuid.Uuid

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    // Set after Koin creates MemoryDomainSync (avoids ctor cycles).
    var memoryDomainSync: MemoryDomainSync? = null

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

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        val existing = memoryDAO.getMemoriesOfAssistant(assistantId)
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        existing.forEach { memoryDomainSync?.enqueueDelete(it.id) }
    }

    suspend fun updateContent(id: String, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record $id not found")
        val newMemory = old.copy(content = content)
        memoryDAO.updateMemory(newMemory)
        memoryDomainSync?.enqueueUpsert(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val entity = MemoryEntity(
            id = Uuid.random().toString(),
            assistantId = assistantId,
            content = content,
        )
        memoryDAO.insertMemory(entity)
        memoryDomainSync?.enqueueUpsert(entity)
        return AssistantMemory(id = entity.id, content = entity.content)
    }

    suspend fun deleteMemory(id: String) {
        memoryDAO.deleteMemory(id)
        memoryDomainSync?.enqueueDelete(id)
    }
}
