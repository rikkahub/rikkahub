package me.rerere.rikkahub.data.rag

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.rag.chunk.Chunker
import kotlin.uuid.Uuid

/**
 * A knowledge base: a named collection of ingested documents queried via vector similarity.
 * Persisted as part of Settings JSON ([me.rerere.rikkahub.data.datastore.Settings.knowledgeBases]).
 *
 * The chunk vectors themselves live in Room (`knowledge_chunk`), keyed by [id] (the store namespace);
 * this model only holds config + a lightweight document manifest for the UI.
 *
 * @property embeddingModelId id of the embedding model (resolved via `findModelById`); must be an
 *   OpenAI-compatible embedding model.
 */
@Serializable
data class KnowledgeBase(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val embeddingModelId: Uuid? = null,
    val chunkSize: Int = Chunker.DEFAULT_CHUNK_SIZE,
    val chunkOverlap: Int = Chunker.DEFAULT_OVERLAP,
    val topK: Int = 4,
    val documents: List<KnowledgeDocument> = emptyList(),
)

@Serializable
data class KnowledgeDocument(
    val id: Uuid = Uuid.random(),
    val fileName: String = "",
    val mime: String = "",
    val chunkCount: Int = 0,
)
