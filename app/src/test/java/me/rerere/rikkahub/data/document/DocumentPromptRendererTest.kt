package me.rerere.rikkahub.data.document

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the core of issue #102: a parser failure / unsupported type must NEVER be wrapped in
 * a `<content>` block as if it were the document's text, and its diagnostic reason must never reach
 * the model prompt. Only [DocumentExtractionResult.Success] produces `<content>`.
 */
class DocumentPromptRendererTest {

    @Test
    fun `Success renders the text inside a content block`() {
        val out = DocumentPromptRenderer.render(
            "a.txt",
            DocumentExtractionResult.Success("real document text", DocumentType.PlainText, "text/plain"),
        )
        assertTrue(out.contains("<content>"))
        assertTrue(out.contains("</content>"))
        assertTrue(out.contains("real document text"))
    }

    @Test
    fun `ParseFailed emits no content block and never leaks the reason`() {
        val out = DocumentPromptRenderer.render(
            "a.pdf",
            DocumentExtractionResult.ParseFailed("boom internal parser stacktrace"),
        )
        assertFalse("must not wrap a failure in <content>", out.contains("<content>"))
        assertFalse("must not leak the parser reason", out.contains("boom"))
        // The user is still made aware the attachment existed.
        assertTrue(out.contains("a.pdf"))
    }

    @Test
    fun `UnsupportedType emits a metadata-only note without content`() {
        val out = DocumentPromptRenderer.render("a.bin", DocumentExtractionResult.UnsupportedType)
        assertFalse(out.contains("<content>"))
        assertTrue(out.contains("a.bin"))
    }

    @Test
    fun `Rejected emits no content block and never leaks the reason`() {
        val out = DocumentPromptRenderer.render(
            "big.txt",
            DocumentExtractionResult.Rejected(DocumentRejectionReason.TooLarge),
        )
        assertFalse(out.contains("<content>"))
        assertFalse(out.contains("TooLarge"))
        assertTrue(out.contains("big.txt"))
    }

    @Test
    fun `Empty emits no content block`() {
        val out = DocumentPromptRenderer.render("a.txt", DocumentExtractionResult.Empty)
        assertFalse(out.contains("<content>"))
        assertTrue(out.contains("a.txt"))
    }
}
