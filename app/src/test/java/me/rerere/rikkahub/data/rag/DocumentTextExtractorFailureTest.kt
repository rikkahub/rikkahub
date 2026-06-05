package me.rerere.rikkahub.data.rag

import me.rerere.document.DocumentExtractionResult
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #83 at the ingestion seam: the value [DocumentTextExtractor.extract] hands to
 * [IngestKnowledgeBaseUseCase] must be a typed result, so a parser failure can never be mistaken for
 * content. [IngestKnowledgeBaseUseCase] only ever chunks `Success.text`; guaranteeing a failure
 * never returns `Success` here is what guarantees no error string is embedded.
 */
class DocumentTextExtractorFailureTest {

    private val docxMime =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    @Test
    fun extract_corruptDocx_isParseFailed_notSuccess() {
        val file = File.createTempFile("not-a-docx", null).apply {
            deleteOnExit()
            writeBytes("definitely not a zip".toByteArray())
        }

        val result = DocumentTextExtractor.extract(file, docxMime)

        assertTrue(
            "corrupt DOCX must extract to ParseFailed, got $result",
            result is DocumentExtractionResult.ParseFailed,
        )
    }

    @Test
    fun extract_emptyPlainText_isEmpty_notSuccess() {
        val file = File.createTempFile("empty-text", ".txt").apply {
            deleteOnExit()
            writeText("   \n\t  ")
        }

        val result = DocumentTextExtractor.extract(file, "text/plain")

        assertTrue(
            "blank plain text must extract to Empty, got $result",
            result is DocumentExtractionResult.Empty,
        )
    }

    @Test
    fun extract_validPlainText_isSuccess() {
        val file = File.createTempFile("valid-text", ".txt").apply {
            deleteOnExit()
            writeText("hello knowledge base")
        }

        val result = DocumentTextExtractor.extract(file, "text/plain")

        assertTrue(
            "non-blank plain text must extract to Success, got $result",
            result is DocumentExtractionResult.Success,
        )
        assertTrue((result as DocumentExtractionResult.Success).text.contains("hello"))
    }
}
