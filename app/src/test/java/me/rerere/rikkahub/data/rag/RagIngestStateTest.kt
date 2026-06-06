package me.rerere.rikkahub.data.rag

import me.rerere.rikkahub.ui.pages.knowledge.ingestResultToState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Proves the RAG ingest result-to-state contract that crosses the VM/UI boundary (issue #105):
 *
 * - a parsed document becomes [RagIngestState.Success] carrying the real chunk count, and
 * - every failure Result becomes a terminal [RagIngestState.Error] with a SAFE, fixed user-facing
 *   string — raw parser/limit text is never surfaced verbatim, and a failure is never mistaken for
 *   success.
 *
 * This is the pure mapper [ingestResultToState]; the live use case is Room-backed and exercised by
 * instrumented tests, so the JVM-runnable guard is the mapper plus the [RagIngestState] shape.
 */
class RagIngestStateTest {
    private val kbId = Uuid.random()
    private val fileName = "report.pdf"

    @Test
    fun `success maps to Success with chunk count`() {
        val doc = KnowledgeDocument(fileName = fileName, mime = "application/pdf", chunkCount = 7)
        val state = ingestResultToState(
            IngestKnowledgeBaseUseCase.Result.Success(doc),
            kbId,
            fileName,
        )

        assertEquals(RagIngestState.Success(kbId, fileName, 7), state)
    }

    @Test
    fun `embedding unavailable maps to safe error`() {
        val state = ingestResultToState(
            IngestKnowledgeBaseUseCase.Result.EmbeddingUnavailable,
            kbId,
            fileName,
        )

        assertEquals(
            RagIngestState.Error(
                kbId,
                fileName,
                "Set an OpenAI-compatible embedding model for this knowledge base first",
            ),
            state,
        )
    }

    @Test
    fun `empty document maps to safe error`() {
        val state = ingestResultToState(
            IngestKnowledgeBaseUseCase.Result.EmptyDocument,
            kbId,
            fileName,
        )

        assertEquals(
            RagIngestState.Error(kbId, fileName, "No extractable text in this file"),
            state,
        )
    }

    @Test
    fun `unsupported type maps to safe error`() {
        val state = ingestResultToState(
            IngestKnowledgeBaseUseCase.Result.UnsupportedType,
            kbId,
            fileName,
        )

        assertEquals(
            RagIngestState.Error(kbId, fileName, "Unsupported file type"),
            state,
        )
    }

    @Test
    fun `parse failed maps to generic error and never leaks the raw parser reason`() {
        val rawReason = "NullPointerException at PdfBox line 4213 /data/user/0/secret.pdf"
        val state = ingestResultToState(
            IngestKnowledgeBaseUseCase.Result.ParseFailed(rawReason),
            kbId,
            fileName,
        )

        assertEquals(
            RagIngestState.Error(kbId, fileName, "Could not parse this file"),
            state,
        )
        // Regression guard: the raw parser text must not reach the user-facing message.
        assertNotEquals(rawReason, (state as RagIngestState.Error).message)
        assertTrue(!state.message.contains("PdfBox"))
    }

    @Test
    fun `rejected (resource limit) is a terminal error, never a success`() {
        val reason = "Document text is too large (over 1000000 characters)"
        val state = ingestResultToState(
            IngestKnowledgeBaseUseCase.Result.Rejected(reason),
            kbId,
            fileName,
        )

        // The Rejected reason is an already-safe diagnostic and is surfaced as-is.
        assertEquals(RagIngestState.Error(kbId, fileName, reason), state)
        // A rejection must NEVER look like a completed ingest (no chunk count, never embedded).
        assertTrue(state !is RagIngestState.Success)
    }

    @Test
    fun `stage enum orders resolving before embedding before persisting`() {
        // Structural guard for the documented progression Resolving -> ... -> Embedding -> Persisting.
        val order = RagIngestState.Stage.entries
        assertTrue(
            order.indexOf(RagIngestState.Stage.Resolving) <
                order.indexOf(RagIngestState.Stage.Embedding),
        )
        assertTrue(
            order.indexOf(RagIngestState.Stage.Embedding) <
                order.indexOf(RagIngestState.Stage.Persisting),
        )
    }
}
