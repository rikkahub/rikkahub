package me.rerere.rikkahub.data.document

import me.rerere.document.DocumentExtractionResult as ParserResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit coverage for the shared extractor (issue #102), plus the relocated issue #83/#84 assertions
 * from the former `data.rag.DocumentTextExtractorFailureTest`: a failure / unsupported / empty input
 * must NEVER come back as [DocumentExtractionResult.Success], so an error string can never be chunked
 * (RAG) or embedded into a prompt (chat).
 *
 * A fake [DocumentParserFacade] injects parser outcomes so the binary paths are testable without real
 * PDF/DOCX/PPTX/EPUB fixtures.
 */
class DocumentTextExtractorTest {

    private val pdfMime = "application/pdf"

    private fun fakeParser(result: ParserResult) = object : DocumentParserFacade {
        override fun parse(type: DocumentType, file: File): ParserResult = result
    }

    private fun tempFile(name: String, bytes: ByteArray): File =
        File.createTempFile(name, null).apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    @Test
    fun `parser ParseFailed maps to ParseFailed not Success`() {
        val file = tempFile("doc", "anything".toByteArray())
        val extractor = DocumentTextExtractor(parsers = fakeParser(ParserResult.ParseFailed("boom")))

        val result = extractor.extract(DocumentSource(file, "doc.pdf", pdfMime))

        assertTrue("got $result", result is DocumentExtractionResult.ParseFailed)
        assertEquals("boom", (result as DocumentExtractionResult.ParseFailed).reason)
    }

    @Test
    fun `parser Empty maps to Empty`() {
        val file = tempFile("doc", "anything".toByteArray())
        val extractor = DocumentTextExtractor(parsers = fakeParser(ParserResult.Empty))

        val result = extractor.extract(DocumentSource(file, "doc.pdf", pdfMime))

        assertTrue("got $result", result is DocumentExtractionResult.Empty)
    }

    @Test
    fun `parser Success carries the resolved type and normalized MIME`() {
        val file = tempFile("doc", "anything".toByteArray())
        val extractor = DocumentTextExtractor(parsers = fakeParser(ParserResult.Success("real text")))

        val result = extractor.extract(DocumentSource(file, "doc.pdf", pdfMime))

        assertTrue("got $result", result is DocumentExtractionResult.Success)
        result as DocumentExtractionResult.Success
        assertEquals("real text", result.text)
        assertEquals(DocumentType.Pdf, result.type)
        assertEquals("application/pdf", result.normalizedMime)
    }

    @Test
    fun `valid plain text is Success of type PlainText`() {
        val file = tempFile("text", "hello knowledge base".toByteArray())
        val result = DocumentTextExtractor().extract(DocumentSource(file, "a.txt", "text/plain"))

        assertTrue("got $result", result is DocumentExtractionResult.Success)
        result as DocumentExtractionResult.Success
        assertTrue(result.text.contains("hello"))
        assertEquals(DocumentType.PlainText, result.type)
        assertEquals("text/plain", result.normalizedMime)
    }

    @Test
    fun `blank plain text is Empty`() {
        val file = tempFile("text", "   \n\t  ".toByteArray())
        val result = DocumentTextExtractor().extract(DocumentSource(file, "a.txt", "text/plain"))

        assertTrue("got $result", result is DocumentExtractionResult.Empty)
    }

    @Test
    fun `unknown binary file is UnsupportedType never Success`() {
        // Issue #84: a binary file (NUL bytes) under an octet-stream MIME with a .bin extension must
        // not reach a raw readText() fallback; it resolves to no type -> UnsupportedType.
        val file = tempFile("unknown", byteArrayOf(0x00, 0x01, 0x02, 0x00, 0x7F, 0x00, 0x10, 0x00))
        val result = DocumentTextExtractor().extract(DocumentSource(file, "a.bin", "application/octet-stream"))

        assertTrue("got $result", result is DocumentExtractionResult.UnsupportedType)
    }

    @Test
    fun `text-MIME but binary bytes is UnsupportedType not Success`() {
        // Even if the MIME claims text/plain, binary bytes (NUL) must fail the #84 sniff and never be
        // decoded into garbage "content".
        val file = tempFile("liar", byteArrayOf(0x00, 0x01, 0x00, 0x02))
        val result = DocumentTextExtractor().extract(DocumentSource(file, "a.txt", "text/plain"))

        assertTrue("got $result", result is DocumentExtractionResult.UnsupportedType)
    }

    @Test
    fun `missing file is ParseFailed`() {
        val file = File("/nonexistent/path/${System.nanoTime()}.txt")
        val result = DocumentTextExtractor().extract(DocumentSource(file, "gone.txt", "text/plain"))

        assertTrue("got $result", result is DocumentExtractionResult.ParseFailed)
    }
}
