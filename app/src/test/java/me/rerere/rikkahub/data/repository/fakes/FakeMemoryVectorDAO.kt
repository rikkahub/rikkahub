package me.rerere.rikkahub.data.repository.fakes

import me.rerere.rikkahub.data.db.dao.MemoryVectorDAO
import me.rerere.rikkahub.data.db.entity.MemoryVectorEntity

/** In-memory [MemoryVectorDAO] for JVM tests. Keyed by memory_id (1:1 with a MemoryEntity). */
class FakeMemoryVectorDAO : MemoryVectorDAO {
    private val rows = LinkedHashMap<Int, MemoryVectorEntity>()

    fun ids(): Set<Int> = rows.keys.toSet()

    override suspend fun getByMemoryIds(ids: List<Int>): List<MemoryVectorEntity> =
        ids.mapNotNull { rows[it] }

    override suspend fun upsert(row: MemoryVectorEntity) {
        rows[row.memoryId] = row
    }

    override suspend fun deleteByMemory(id: Int) {
        rows.remove(id)
    }

    override suspend fun deleteByMemoryIds(ids: List<Int>) {
        ids.forEach { rows.remove(it) }
    }
}
