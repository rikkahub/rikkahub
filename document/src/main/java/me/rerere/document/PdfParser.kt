package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

object PdfParser {
    fun parserPdf(file: File): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        val pages = document.countPages()
        val result = StringBuilder()
        for (i in 0 until pages) {
            val page = document.loadPage(i).toStructuredText()
            result.append("---")
            result.append("Page ${i + 1}:\n")
            result.append(page.asText())
            result.appendLine()
        }
        return result.toString()
    }

    /**
     * Typed extraction: a corrupt PDF (which makes [parserPdf] throw) becomes
     * [DocumentExtractionResult.ParseFailed] rather than propagating as an opaque exception, keeping
     * PDF consistent with the other parsers for RAG ingestion. The page wrapper "---Page N:" markers
     * mean a structurally-empty PDF still yields non-blank text; only a truly empty extraction maps
     * to [DocumentExtractionResult.Empty].
     */
    fun parseTyped(file: File): DocumentExtractionResult {
        return try {
            val text = parserPdf(file)
            if (text.isBlank()) DocumentExtractionResult.Empty
            else DocumentExtractionResult.Success(text)
        } catch (e: Exception) {
            DocumentExtractionResult.ParseFailed(e.message ?: "Error parsing PDF")
        }
    }
}
