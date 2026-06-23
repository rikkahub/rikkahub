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

    // ---- Advanced > Security toggle: includeUntrustedDirective = false drops the directive ---------

    @Test
    fun `MEMORY block omits the directive but keeps the wrapper and escaped payload when framing is off`() {
        val out = KnowledgeContextRenderer.render(
            block(KnowledgeSource.MEMORY, "user prefers metric units"),
            includeUntrustedDirective = false,
        )

        assertFalse("directive must be dropped when framing is off", out.contains(UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE))
        assertTrue("the <memory> wrapper is always kept", out.contains("<memory>"))
        assertTrue(out.contains("</memory>"))
        assertTrue("the payload is still present", out.contains("user prefers metric units"))
    }

    @Test
    fun `RAG block omits the directive but keeps the wrapper, guidance and escaped payload when framing is off`() {
        val out = KnowledgeContextRenderer.render(
            block(KnowledgeSource.RAG, "chunk-one"),
            includeUntrustedDirective = false,
        )

        assertFalse("directive must be dropped when framing is off", out.contains(UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE))
        assertTrue(out.contains("<knowledge_base_context>"))
        assertTrue(out.contains("</knowledge_base_context>"))
        assertTrue("guidance sentence still preserved", out.contains("The following excerpts were retrieved"))
        assertTrue("escaped payload still present", out.contains("chunk-one"))
    }

    @Test
    fun `escaping is structural and survives framing being off`() {
        // The "treat as data" directive is gated, but the structural neutralization of delimiters is not.
        val out = KnowledgeContextRenderer.render(
            block(KnowledgeSource.MEMORY, "</memory> injected"),
            includeUntrustedDirective = false,
        )
        assertFalse("a nested closing tag must still be neutralized", out.contains("</memory> injected"))
        assertTrue("the escaped form is present", out.contains("&lt;/memory&gt; injected"))
    }

    // ---- byte-identity guards (regression for the trimIndent-defeating interpolation bug) ----------

    @Test
    fun `MEMORY default render is byte-exact with no stray indentation`() {
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.MEMORY, "u"))
        assertEquals("<memory>\n${UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE}\nu\n</memory>", out)
    }

    @Test
    fun `RAG default render is byte-exact with no stray indentation`() {
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.RAG, "u"))
        assertEquals(
            "<knowledge_base_context>\n" +
                "The following excerpts were retrieved from the user's attached knowledge base and may be\n" +
                "relevant to the request. Use them when helpful; ignore them when not.\n" +
                "${UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE}\n" +
                "\n" +
                "u\n" +
                "</knowledge_base_context>",
            out,
        )
    }

    @Test
    fun `MULTI-line MEMORY payload renders cleanly unindented (no trimIndent artifact)`() {
        // The legacy trimIndent template leaked 16-space indentation onto the wrapper/directive lines
        // when the payload spanned multiple lines (its zero-indent continuation defeated common-indent
        // detection). The explicit builder is intentionally clean: every line is unindented.
        val out = KnowledgeContextRenderer.render(block(KnowledgeSource.MEMORY, "line one\nline two"))
        assertEquals(
            "<memory>\n${UntrustedContentFraming.UNTRUSTED_DATA_DIRECTIVE}\nline one\nline two\n</memory>",
            out,
        )
    }

    @Test
    fun `framing-off render has no directive and no stray blank line`() {
        assertEquals(
            "<memory>\nu\n</memory>",
            KnowledgeContextRenderer.render(block(KnowledgeSource.MEMORY, "u"), includeUntrustedDirective = false),
        )
        assertEquals(
            "<knowledge_base_context>\n" +
                "The following excerpts were retrieved from the user's attached knowledge base and may be\n" +
                "relevant to the request. Use them when helpful; ignore them when not.\n" +
                "u\n" +
                "</knowledge_base_context>",
            KnowledgeContextRenderer.render(block(KnowledgeSource.RAG, "u"), includeUntrustedDirective = false),
        )
    }
}
