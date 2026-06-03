package me.rerere.rikkahub.data.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findKnowledgeBase
import me.rerere.rikkahub.data.rag.chunk.Chunker
import me.rerere.rikkahub.data.rag.store.Chunk
import java.io.File
import kotlin.uuid.Uuid

/**
 * Orchestrates ingestion of one document into a knowledge base:
 * parse (:document parsers) -> chunk ([Chunker]) -> embed + persist ([RoomVectorStore]).
 *
 * Progress is reported through [onProgress] (0.0..1.0). Returns the new [KnowledgeDocument] manifest
 * entry on success; the caller persists it onto the [KnowledgeBase] in Settings.
 */
class IngestKnowledgeBaseUseCase(
    private val settingsStore: SettingsStore,
    private val storeFactory: KnowledgeStoreFactory,
) {
    sealed interface Result {
        data class Success(val document: KnowledgeDocument) : Result

        /** Embedding model unset or not OpenAI-compatible (embeddings unsupported). */
        data object EmbeddingUnavailable : Result

        /** Parsed document had no usable text. */
        data object EmptyDocument : Result
    }

    suspend operator fun invoke(
        knowledgeBaseId: Uuid,
        file: File,
        fileName: String,
        mime: String,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val kb = settings.findKnowledgeBase(knowledgeBaseId)
            ?: return@withContext Result.EmbeddingUnavailable
        val store = storeFactory.buildStore(kb, settings)
            ?: return@withContext Result.EmbeddingUnavailable

        onProgress(0f)
        val text = DocumentTextExtractor.extract(file, mime)
        val pieces = Chunker.chunk(text, chunkSize = kb.chunkSize, overlap = kb.chunkOverlap)
        if (pieces.isEmpty()) return@withContext Result.EmptyDocument

        val docId = Uuid.random()
        val docIdStr = docId.toString()
        // Embed + persist incrementally so progress reflects real work and a large doc doesn't hold
        // every vector in memory before the first write. A mid-loop failure (e.g. embedding network
        // error) would otherwise leave already-written chunks orphaned — with no manifest entry to
        // delete them later — yet still returned by search(). So roll back this doc's rows on any
        // failure before rethrowing, keeping ingestion all-or-nothing per document.
        try {
            pieces.forEachIndexed { index, content ->
                val chunk = Chunk(
                    chunkId = Uuid.random().toString(),
                    docId = docIdStr,
                    sourceRef = fileName,
                    chunkIndex = index,
                    content = content,
                )
                store.add(listOf(chunk), namespace = kb.id.toString())
                onProgress((index + 1).toFloat() / pieces.size)
            }
        } catch (e: Throwable) {
            // Rollback must run even when the failure is cancellation (caller navigates away mid-ingest,
            // cancelling viewModelScope). A plain deleteDocument() call here would itself suspend on a
            // cancelled coroutine and abort before deleteByDoc executes, orphaning the just-written rows
            // — and RoomVectorStore.search() loads by kbId regardless of the Settings manifest, so those
            // orphans stay retrieval-visible forever. NonCancellable guarantees the cleanup completes.
            withContext(NonCancellable) {
                store.deleteDocument(docIdStr, namespace = kb.id.toString())
            }
            throw e
        }

        Result.Success(
            KnowledgeDocument(
                id = docId,
                fileName = fileName,
                mime = mime,
                chunkCount = pieces.size,
            )
        )
    }
}
