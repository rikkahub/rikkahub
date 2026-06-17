package me.rerere.rikkahub.data.repository.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity

/** In-memory [MemoryDAO] for JVM tests. A plain id-keyed map; mirrors the Room DAO's row semantics. */
class FakeMemoryDAO : MemoryDAO {
    private val rows = LinkedHashMap<Int, MemoryEntity>()
    private var nextId = 1

    fun seed(entity: MemoryEntity): Int {
        val id = if (entity.id != 0) entity.id else nextId++
        rows[id] = entity.copy(id = id)
        if (id >= nextId) nextId = id + 1
        return id
    }

    override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
        flowOf(rows.values.filter { it.assistantId == assistantId })

    override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
        rows.values.filter { it.assistantId == assistantId }

    override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = flowOf(rows.values.toList())

    override suspend fun getAllMemories(): List<MemoryEntity> = rows.values.toList()

    override suspend fun getMemoryById(id: Int): MemoryEntity? = rows[id]

    override suspend fun insertMemory(memory: MemoryEntity): Long = seed(memory).toLong()

    override suspend fun updateMemory(memory: MemoryEntity) {
        rows[memory.id] = memory
    }

    override suspend fun deleteMemory(id: Int) {
        rows.remove(id)
    }

    override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        rows.values.removeAll { it.assistantId == assistantId }
    }
}
