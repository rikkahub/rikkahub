package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted vector-store row for a single knowledge-base chunk.
 *
 * The embedding is stored as a comma-joined string of floats ([embedding]) — Koog's `Vector` is not
 * a Room-persistable type, so primitives are stored and the vector reconstructed at query time.
 * Queries are scoped by [kbId] (the Koog "namespace").
 */
@Entity(
    tableName = "knowledge_chunk",
    indices = [Index("kb_id")],
)
data class KnowledgeChunkEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("kb_id")
    val kbId: String,
    @ColumnInfo("doc_id")
    val docId: String,
    @ColumnInfo("source_ref")
    val sourceRef: String,
    @ColumnInfo("chunk_index")
    val chunkIndex: Int,
    @ColumnInfo("text")
    val text: String,
    @ColumnInfo("embedding")
    val embedding: String,
    @ColumnInfo("embedding_model")
    val embeddingModel: String,
)
