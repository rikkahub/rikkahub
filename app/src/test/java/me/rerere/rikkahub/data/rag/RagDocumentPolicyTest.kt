package me.rerere.rikkahub.data.rag

import me.rerere.rikkahub.data.document.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the RAG document-acceptance policy: which `(fileName, mime)` pairs the knowledge-base
 * ingestion path accepts, and that the advisory picker filter agrees with that authoritative
 * decision. The underlying resolver internals are already proven by [DocumentTypeResolverTest];
 * this test only proves the RAG policy layer + its delegation, plus the picker/validation invariant.
 */
class RagDocumentPolicyTest {

    @Test
    fun `supported file types resolve to their DocumentType`() {
        val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val pptxMime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        val cases = listOf(
            Triple("a.pdf", "application/pdf", DocumentType.Pdf),
            Triple("a.docx", docxMime, DocumentType.Docx),
            Triple("a.pptx", pptxMime, DocumentType.Pptx),
            Triple("a.epub", "application/epub+zip", DocumentType.Epub),
            Triple("a.md", "text/markdown", DocumentType.PlainText),
            Triple("a.txt", "text/plain", DocumentType.PlainText),
            // A provider may hand a meaningful extension with a useless octet-stream MIME; the
            // resolver's extension fallback must still flow through the policy.
            Triple("a.pdf", "application/octet-stream", DocumentType.Pdf),
        )
        cases.forEach { (name, mime, expected) ->
            assertEquals("$name / $mime", expected, RagDocumentPolicy.resolve(name, mime))
        }
    }

    @Test
    fun `unsupported file types are rejected as null`() {
        val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val cases = listOf(
            "a.png" to "image/png",
            "a.bin" to "application/octet-stream",
            "a.xlsx" to xlsxMime,
            "a.doc" to "application/msword",
            "a.zip" to "application/zip",
            // application/json and application/xml are NOT mapped by the reused #102 resolver
            // (neither is a text/* subtype nor an allowlisted extension), so they are unsupported.
            "a.json" to "application/json",
            "a.xml" to "application/xml",
        )
        cases.forEach { (name, mime) ->
            assertNull("$name / $mime", RagDocumentPolicy.resolve(name, mime))
        }
    }

    @Test
    fun `picker MIME filter excludes the open wildcard`() {
        // Regression guard for the picker-drops-wildcard acceptance criterion: launching the
        // document picker with "*/*" let users pick files the pipeline can never ingest.
        assertFalse(RagDocumentPolicy.pickerMimeTypes.contains("*/*"))
    }

    @Test
    fun `every advertised picker MIME is accepted by the authoritative resolver`() {
        // Pins the advisory-vs-authoritative invariant: the picker must not hint at a type the VM
        // would then reject. A "text/*" wildcard entry is exercised with a concrete text subtype.
        RagDocumentPolicy.pickerMimeTypes.forEach { mime ->
            val probeMime = if (mime == "text/*") "text/plain" else mime
            assertNotNull(
                "picker advertises $mime but resolver rejects it",
                RagDocumentPolicy.resolve("probe", probeMime),
            )
        }
    }
}
