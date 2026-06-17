package me.rerere.ai.runtime.knowledge

import me.rerere.common.text.UntrustedContentFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks source-labeled rendering invariants for each knowledge source.
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
    fun `RAG block is wrapped in knowledge_base_context with the preserved guidance and escaped payload`() {
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.RAG, "chunk-one\n\n---\n\nchunk-two"))

        assertTrue(out.contains("<knowledge_base_context>"))
        assertTrue(out.contains("</knowledge_base_context>"))
        assertTrue(
            "guidance sentence preserved",
            out.contains("The following excerpts were retrieved from the user's attached knowledge base"),
        )
        assertTrue("directive sentence present", out.contains(UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE))
        assertTrue("escaped payload still present", out.contains("chunk-one"))
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
    fun `MEMORY block is wrapped in a distinct memory label with a directive`() {
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.MEMORY, "user prefers metric units"))

        assertTrue(out.contains("<memory>"))
        assertTrue(out.contains("</memory>"))
        assertTrue(out.contains(UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE))
        assertTrue(out.contains("user prefers metric units"))
        // Distinct from the other two sources.
        assertFalse(out.contains("<knowledge_base_context>"))
        assertFalse(out.contains("## user sent a file:"))
    }
}
