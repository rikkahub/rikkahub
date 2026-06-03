package me.rerere.rikkahub.data.rag

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
    fun extract(file: File, mime: String): String = when (mime) {
        "application/pdf" -> PdfParser.parserPdf(file)
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parse(file)
        "application/epub+zip" -> EpubParser.parse(file)
        else -> file.readText()
    }
}
