package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dedicated vector-store row for a single memory (Memory v2 relevance recall, issue #210).
 *
 * Kept in its OWN table — memory is not a knowledge base ([KnowledgeChunkEntity]); a memory vector
 * is 1:1 with a [MemoryEntity], not a chunk of an ingested document, so the two concerns get
 * separate stores rather than a shared overloaded one.
 *
 * The embedding is stored as a comma-joined string of floats ([embedding]) — Koog's `Vector` is not
 * a Room-persistable type — using the same codec as [KnowledgeChunkEntity]
 * ([me.rerere.rikkahub.data.rag.store.RoomVectorStore.encodeVector]/`decodeVector`).
 *
 * A stored vector is only USABLE for ranking when BOTH freshness keys still hold:
 *  - [contentHash] == hash of the memory's CURRENT content (content edited since embed ⇒ stale),
 *  - [embeddingSpace] == the current `embeddingSpaceLabel(baseUrl, modelId)` (model/endpoint changed
 *    ⇒ incomparable space).
 * Any mismatch ⇒ the memory is treated as un-embedded and falls back to recency ranking, then is
 * re-embedded lazily on the next write.
 */
@Entity(tableName = "memory_vector", indices = [Index("memory_id")])
data class MemoryVectorEntity(
    @PrimaryKey
    @ColumnInfo("memory_id")
    val memoryId: Int,
    @ColumnInfo("content_hash")
    val contentHash: String,
    @ColumnInfo("embedding")
    val embedding: String,
    @ColumnInfo("embedding_space")
    val embeddingSpace: String,
)
