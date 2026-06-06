package me.rerere.rikkahub.data.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.document.DocumentExtractionResult
import me.rerere.rikkahub.data.document.DocumentSource
import me.rerere.rikkahub.data.document.DocumentTextExtractor
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
 * Progress is reported through [onStage] as a typed (stage, fraction-in-[0,1]) pair so the caller can
 * show which step is running, not just a bare number. Returns the new [KnowledgeDocument] manifest
 * entry on success; the caller persists it onto the [KnowledgeBase] in Settings.
 */
class IngestKnowledgeBaseUseCase(
    private val settingsStore: SettingsStore,
    private val storeFactory: KnowledgeStoreFactory,
    private val documentExtractor: DocumentTextExtractor = DocumentTextExtractor(),
) {
    sealed interface Result {
        data class Success(val document: KnowledgeDocument) : Result

        /** Embedding model unset or not OpenAI-compatible (embeddings unsupported). */
        data object EmbeddingUnavailable : Result

        /** Parsed document had no usable text. */
        data object EmptyDocument : Result

        /**
         * The file is not one of the supported document types (and did not sniff as plain text). RAG
         * is strict: no fallback read, no ingestion (issue #102).
         */
        data object UnsupportedType : Result

        /** Parser failed; [reason] is for user-facing diagnostics only and is never embedded. */
        data class ParseFailed(val reason: String) : Result

        /**
         * Ingestion exceeded a resource limit (extracted text too large, too many chunks) and was
         * rejected before any embedding/store work (issue #84). [reason] is a user-facing diagnostic
         * only and is never embedded, same discipline as [ParseFailed].
         */
        data class Rejected(val reason: String) : Result
    }

    suspend operator fun invoke(
        knowledgeBaseId: Uuid,
        file: File,
        fileName: String,
        mime: String,
        onStage: (RagIngestState.Stage, Float) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val kb = settings.findKnowledgeBase(knowledgeBaseId)
            ?: return@withContext Result.EmbeddingUnavailable
        val store = storeFactory.buildStore(kb, settings)
            ?: return@withContext Result.EmbeddingUnavailable

        onStage(RagIngestState.Stage.Extracting, 0f)
        // Only Success text may be chunked/embedded. A ParseFailed reason must never reach the
        // store — that is the whole point of the typed extraction: an error string can no longer be
        // mistaken for content (issue #83).
        val text = when (
            val extraction = documentExtractor.extract(DocumentSource(file, fileName, mime))
        ) {
            is DocumentExtractionResult.ParseFailed -> return@withContext Result.ParseFailed(extraction.reason)
            is DocumentExtractionResult.Rejected -> return@withContext Result.Rejected(extraction.reason.name)
            DocumentExtractionResult.UnsupportedType -> return@withContext Result.UnsupportedType
            DocumentExtractionResult.Empty -> return@withContext Result.EmptyDocument
            is DocumentExtractionResult.Success -> extraction.text
        }
        // Reject pathologically large extracted text BEFORE chunking/embedding so a huge document
        // can't generate runaway embedding calls/cost (issue #84).
        if (!RagIngestLimits.enforceMaxChars(text.length)) {
            return@withContext Result.Rejected(
                "Document text is too large (over ${RagIngestLimits.MAX_EXTRACTED_CHARS} characters)"
            )
        }
        onStage(RagIngestState.Stage.Chunking, 0f)
        val pieces = Chunker.chunk(text, chunkSize = kb.chunkSize, overlap = kb.chunkOverlap)
        if (pieces.isEmpty()) return@withContext Result.EmptyDocument
        // Reject too many chunks BEFORE the embed loop / first store.add(), so an oversized document
        // can't drive thousands of embedding calls before any work is wasted (issue #84).
        if (!RagIngestLimits.enforceMaxChunks(pieces.size)) {
            return@withContext Result.Rejected(
                "Document produced too many chunks (over ${RagIngestLimits.MAX_CHUNKS})"
            )
        }

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
                onStage(RagIngestState.Stage.Embedding, (index + 1).toFloat() / pieces.size)
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

        onStage(RagIngestState.Stage.Persisting, 1f)
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
