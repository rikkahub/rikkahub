package me.rerere.rikkahub.data.ai.runtime

import me.rerere.ai.runtime.contract.AssistantMemory
import me.rerere.ai.runtime.contract.ConversationReader
import me.rerere.ai.runtime.contract.ConversationSummary
import me.rerere.ai.runtime.contract.MemoryReader
import me.rerere.ai.runtime.contract.MemoryScope
import me.rerere.ai.runtime.contract.MemoryVector
import me.rerere.ai.runtime.contract.MemoryWriter
import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

/**
 * Binds the neutral [ConversationReader] over [ConversationRepository] (issue #243 slice 3). Maps the
 * app `Conversation` onto [ConversationSummary] at the boundary; no Room entity crosses it.
 */
class AppConversationReader(
    private val repository: ConversationRepository,
) : ConversationReader {
    override suspend fun recentConversations(assistantId: Uuid, limit: Int): List<ConversationSummary> =
        repository.getRecentConversations(assistantId, limit).map {
            ConversationSummary(id = it.id, assistantId = it.assistantId, title = it.title)
        }
}

/** Resolve the app's string memory partition id for a neutral [MemoryScope]. */
private fun MemoryScope.toPartitionId(): String = when (this) {
    is MemoryScope.AssistantScoped -> assistantId.toString()
    MemoryScope.Global -> MemoryRepository.GLOBAL_MEMORY_ID
}

/**
 * Binds the neutral [MemoryReader] over [MemoryRepository] (issue #243 slice 3). Maps entities onto
 * the neutral [RecalledMemory] / [MemoryVector]; translates [MemoryScope] to the repo's string id.
 */
class AppMemoryReader(
    private val repository: MemoryRepository,
) : MemoryReader {
    override suspend fun recalledMemories(scope: MemoryScope): List<RecalledMemory> =
        repository.getRecalledMemoriesOfAssistant(scope.toPartitionId()).map {
            RecalledMemory(id = it.id, content = it.content, createdAt = it.createdAt, updatedAt = it.updatedAt)
        }

    override suspend fun vectors(memoryIds: List<Int>): List<MemoryVector> =
        repository.getMemoryVectors(memoryIds).map {
            MemoryVector(
                memoryId = it.memoryId,
                embedding = it.embedding,
                contentHash = it.contentHash,
                embeddingSpace = it.embeddingSpace,
            )
        }
}

/** Binds the neutral [MemoryWriter] over [MemoryRepository] (issue #243 slice 3). */
class AppMemoryWriter(
    private val repository: MemoryRepository,
) : MemoryWriter {
    override suspend fun add(scope: MemoryScope, content: String): AssistantMemory =
        repository.addMemory(scope.toPartitionId(), content).let { AssistantMemory(it.id, it.content) }

    override suspend fun update(id: Int, content: String): AssistantMemory =
        repository.updateContent(id, content).let { AssistantMemory(it.id, it.content) }

    override suspend fun delete(id: Int) = repository.deleteMemory(id)
}
