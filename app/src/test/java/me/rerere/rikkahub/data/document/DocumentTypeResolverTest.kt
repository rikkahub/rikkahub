package me.rerere.rikkahub.data.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The single source of truth for MIME/extension routing (issue #102). Proving it once here proves
 * the consistency acceptance criterion for BOTH chat and RAG, since both call this same resolver.
 */
class DocumentTypeResolverTest {

    @Test
    fun `known MIME types resolve to their DocumentType`() {
        val cases = listOf(
            "application/pdf" to DocumentType.Pdf,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DocumentType.Docx,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to DocumentType.Pptx,
            "application/epub+zip" to DocumentType.Epub,
            "text/plain" to DocumentType.PlainText,
        )
        cases.forEach { (mime, expected) ->
            assertEquals("mime=$mime", expected, DocumentTypeResolver.resolve("file", mime))
        }
    }

    @Test
    fun `any text subtype resolves to PlainText`() {
        assertEquals(DocumentType.PlainText, DocumentTypeResolver.resolve("a.csv", "text/csv"))
        assertEquals(DocumentType.PlainText, DocumentTypeResolver.resolve("a", "text/markdown"))
    }

    @Test
    fun `MIME with charset parameter is normalized`() {
        assertEquals(
            DocumentType.PlainText,
            DocumentTypeResolver.resolve("a.txt", "text/plain; charset=utf-8"),
        )
    }

    @Test
    fun `generic or null MIME falls back to file extension`() {
        // A provider may hand a meaningful name with a useless octet-stream MIME.
        assertEquals(DocumentType.Pdf, DocumentTypeResolver.resolve("a.pdf", "application/octet-stream"))
        assertEquals(DocumentType.Docx, DocumentTypeResolver.resolve("a.docx", null))
        assertEquals(DocumentType.Pptx, DocumentTypeResolver.resolve("a.pptx", "application/octet-stream"))
        assertEquals(DocumentType.Epub, DocumentTypeResolver.resolve("a.epub", null))
        assertEquals(DocumentType.PlainText, DocumentTypeResolver.resolve("a.txt", "application/octet-stream"))
        assertEquals(DocumentType.PlainText, DocumentTypeResolver.resolve("a.md", null))
    }

    @Test
    fun `unknown binary with no known extension is unsupported`() {
        assertNull(DocumentTypeResolver.resolve("a.bin", "application/octet-stream"))
        assertNull(DocumentTypeResolver.resolve("noext", "image/png"))
        assertNull(DocumentTypeResolver.resolve("a.png", null))
    }
}
