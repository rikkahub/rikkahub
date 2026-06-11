package me.rerere.ai.runtime.contract

import kotlin.uuid.Uuid

/**
 * Neutral mirror of the app `RecalledMemory` recall-path carrier (issue #243 §B). Deliberately
 * NON-`@Serializable` (design revision #4) — like its app counterpart it is never serialized; the
 * timestamps feed the recall age render only.
 */
data class RecalledMemory(
    val id: Int,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Neutral mirror of the app `MemoryVectorEntity` recall row (issue #243 §B). No Room type leaks. */
data class MemoryVector(
    val memoryId: Int,
    val embedding: String,
    val contentHash: String,
    val embeddingSpace: String,
)

/** Neutral mirror of the app `AssistantMemory` tool-result DTO (issue #243 §B). */
data class AssistantMemory(
    val id: Int,
    val content: String,
)

/**
 * Which memory partition a read/write targets (issue #243 §B). Encodes the app's
 * assistant-isolated vs global-shared split as a neutral descriptor, replacing the ad-hoc
 * "assistantId string vs global sentinel" the app passes around.
 */
sealed interface MemoryScope {
    data class AssistantScoped(val assistantId: Uuid) : MemoryScope
    data object Global : MemoryScope
}

/** Neutral read port over the memory store (issue #243 §B). */
interface MemoryReader {
    suspend fun recalledMemories(scope: MemoryScope): List<RecalledMemory>
    suspend fun vectors(memoryIds: List<Int>): List<MemoryVector>
}

/** Neutral write port over the memory store (issue #243 §B). */
interface MemoryWriter {
    suspend fun add(scope: MemoryScope, content: String): AssistantMemory
    suspend fun update(id: Int, content: String): AssistantMemory
    suspend fun delete(id: Int)
}
