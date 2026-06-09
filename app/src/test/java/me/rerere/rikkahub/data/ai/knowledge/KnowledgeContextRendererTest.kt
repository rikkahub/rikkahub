package me.rerere.rikkahub.data.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks invariant 4 (issue #141): each knowledge source renders with a visually distinct label so
 * memory insight can never masquerade as document evidence — and the RAG wrapper reproduces the
 * legacy `KnowledgeRetrievalTransformer.buildContextBlock` text exactly (no-regression, PBT P7).
 */
class KnowledgeContextRendererTest {

    private fun block(source: KnowledgeSource, content: String) = KnowledgeContextBlock(
        source = source,
        scope = KnowledgeScope.MESSAGE,
        title = null,
        content = content,
        priority = 0,
        estimatedTokens = 0,
    )

    @Test
    fun `RAG block is wrapped in knowledge_base_context with the preserved guidance and chunk text`() {
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.RAG, "chunk-one\n\n---\n\nchunk-two"))

        assertTrue(out.contains("<knowledge_base_context>"))
        assertTrue(out.contains("</knowledge_base_context>"))
        assertTrue(
            "guidance sentence preserved",
            out.contains("The following excerpts were retrieved from the user's attached knowledge base"),
        )
        assertTrue("chunk text present", out.contains("chunk-one"))
        assertTrue("chunk text present", out.contains("chunk-two"))
    }

    @Test
    fun `RAG rendering is byte-identical to the legacy buildContextBlock format`() {
        // The exact string the old KnowledgeRetrievalTransformer produced for the same joined chunks.
        val joined = "alpha\n\n---\n\nbeta"
        val legacy = """
            <knowledge_base_context>
            The following excerpts were retrieved from the user's attached knowledge base and may be
            relevant to the request. Use them when helpful; ignore them when not.

            $joined
            </knowledge_base_context>
        """.trimIndent()

        assertEquals(legacy, KnowledgeContextRenderer.render(block(KnowledgeSource.RAG, joined)))
    }

    @Test
    fun `ATTACHMENT block is emitted verbatim with no knowledge_base_context wrapper`() {
        // content is already DocumentPromptRenderer output; its own header IS the attachment label.
        val rendered = "## user sent a file: report.pdf\n<content>\n```\nbody\n```\n</content>"
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.ATTACHMENT, rendered))

        assertEquals(rendered, out)
        assertFalse("attachments must not look like RAG evidence", out.contains("<knowledge_base_context>"))
        assertTrue("attachment header retained", out.contains("## user sent a file: report.pdf"))
    }

    @Test
    fun `MEMORY block is wrapped in a distinct memory label`() {
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.MEMORY, "user prefers metric units"))

        assertTrue(out.contains("<memory>"))
        assertTrue(out.contains("</memory>"))
        assertTrue(out.contains("user prefers metric units"))
        // Distinct from the other two sources.
        assertFalse(out.contains("<knowledge_base_context>"))
        assertFalse(out.contains("## user sent a file:"))
    }
}
