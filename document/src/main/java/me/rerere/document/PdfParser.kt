package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

object PdfParser {
    /**
     * Matches the per-page header that [parserPdf] injects for every page: the literal "---"
     * concatenated with "Page N:" on its own line (see [parserPdf]). Stripping this synthetic marker
     * before counting glyphs is what makes a fully scanned/image-only PDF (markers but no real text)
     * detectable as empty.
     */
    private val PAGE_MARKER_REGEX = Regex("""^---Page \d+:\s*$""", RegexOption.MULTILINE)

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
     * Count of non-whitespace glyphs in [fullText] after removing the synthetic "---Page N:" markers
     * that [parserPdf] prepends to every page. This is the "meaningful text" measure: it ignores the
     * page wrappers and whitespace, so a scanned PDF (markers only, no glyphs) counts as 0.
     *
     * Pure (no MuPDF / native lib involved), so the emptiness decision is directly unit-testable.
     */
    fun meaningfulGlyphCount(fullText: String): Int =
        fullText.replace(PAGE_MARKER_REGEX, "").count { !it.isWhitespace() }

    /**
     * Whether the assembled PDF text carries no meaningful content. Replaces a naive `isBlank()`
     * check: the latter is fooled by the per-page "---Page N:" markers, which make an image-only PDF
     * non-blank and therefore misclassified as content.
     */
    fun isEffectivelyEmpty(fullText: String): Boolean = meaningfulGlyphCount(fullText) == 0

    /**
     * Typed extraction: a corrupt PDF (which makes [parserPdf] throw) becomes
     * [DocumentExtractionResult.ParseFailed] rather than propagating as an opaque exception, keeping
     * PDF consistent with the other parsers for RAG ingestion.
     *
     * Emptiness is decided by [isEffectivelyEmpty] (per-page meaningful-glyph count), not `isBlank()`:
     * [parserPdf] injects a "---Page N:" marker for every page, so a fully scanned/image-only PDF
     * yields a non-blank string of markers with zero glyphs. Counting only real glyphs maps such a
     * PDF to [DocumentExtractionResult.Empty] instead of leaking marker-only noise as
     * [DocumentExtractionResult.Success] (which RAG would then chunk and embed). A PDF with a real
     * text layer keeps a positive glyph count and is unaffected.
     */
    fun parseTyped(file: File): DocumentExtractionResult {
        return try {
            val text = parserPdf(file)
            if (isEffectivelyEmpty(text)) DocumentExtractionResult.Empty
            else DocumentExtractionResult.Success(text)
        } catch (e: Exception) {
            DocumentExtractionResult.ParseFailed(e.message ?: "Error parsing PDF")
        }
    }
}
