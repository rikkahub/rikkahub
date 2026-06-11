package me.rerere.rikkahub.data.ai.memory

import ai.koog.embeddings.base.Embedder
import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.runtime.memory.memoryContentHash
import me.rerere.rikkahub.data.db.entity.MemoryVectorEntity
import me.rerere.rikkahub.data.rag.store.RoomVectorStore

private const val TAG = "MemoryVectorWriter"

/**
 * Embeds [content] for memory [memoryId] and builds its `memory_vector` row, returning `null` when
 * embedding fails (issue #210 §7 — write embedding failure).
 *
 * Best-effort by contract: the content row is the source of truth and is written before this runs, so
 * a transient provider/network failure here must NOT propagate (it would surface as a memory_tool
 * error and abort the write). The failure is logged and degraded to "no vector row" ⇒ recall falls
 * back to recency for this memory until a later write re-embeds it. [CancellationException] is
 * re-thrown so coroutine cancellation is never swallowed.
 *
 * Pure of any DB transaction (the caller upserts the returned row outside the txn), so the embed-fail
 * contract is unit-testable headlessly with a throwing embedder (property P12).
 */
suspend fun buildMemoryVectorRow(
    embedder: Embedder,
    memoryId: Int,
    content: String,
    embeddingSpace: String,
): MemoryVectorEntity? {
    val vector = try {
        embedder.embed(content)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "buildMemoryVectorRow: embed failed for memory #$memoryId; falling back to recency", e)
        return null
    }
    return MemoryVectorEntity(
        memoryId = memoryId,
        contentHash = memoryContentHash(content),
        embedding = RoomVectorStore.encodeVector(vector),
        embeddingSpace = embeddingSpace,
    )
}
