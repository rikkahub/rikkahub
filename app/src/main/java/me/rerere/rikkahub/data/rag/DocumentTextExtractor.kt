package me.rerere.rikkahub.data.rag

import me.rerere.document.DocumentExtractionResult
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

/**
 * Routes a file to the appropriate :document parser by MIME type, falling back to plain-text read.
 * Mirrors the routing in
 * [me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer.readDocumentContent] but is
 * reused by RAG ingestion (separate concern, separate entry point).
 */
object DocumentTextExtractor {
    fun extract(file: File, mime: String): DocumentExtractionResult = when (mime) {
        "application/pdf" -> PdfParser.parseTyped(file)
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parseTyped(file)
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parseTyped(file)
        "application/epub+zip" -> EpubParser.parseTyped(file)
        // Unknown MIME: only read as plain text when the bytes actually sniff as text. A raw
        // file.readText() would decode a binary file into garbage "content" that then gets chunked
        // and embedded (issue #84). safeReadPlainText returns null for binary-looking or oversized
        // input, which we surface as ParseFailed so it never reaches the store.
        else -> when (val text = RagIngestLimits.safeReadPlainText(file)) {
            null -> DocumentExtractionResult.ParseFailed("File is not readable text")
            else -> if (text.isBlank()) DocumentExtractionResult.Empty
            else DocumentExtractionResult.Success(text)
        }
    }
}
