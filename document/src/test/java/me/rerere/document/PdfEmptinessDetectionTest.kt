package me.rerere.document

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for issue #189 (design comment `architecture-design:113`, Step 1, properties P6/P7):
 * the dead `Empty` branch in [PdfParser.parseTyped].
 *
 * [PdfParser.parserPdf] prepends a synthetic "---Page N:" marker to EVERY page, so a fully
 * scanned/image-only PDF (an image per page, no text layer) yields a NON-BLANK string of markers
 * with zero real glyphs. The old `text.isBlank()` predicate therefore misclassified such a PDF as
 * [DocumentExtractionResult.Success], leaking marker-only noise that RAG then chunked and embedded
 * — the opposite of the #83/#102 "non-content must not be embedded" discipline.
 *
 * The probe under test ([PdfParser.meaningfulGlyphCount] / [PdfParser.isEffectivelyEmpty]) is PURE:
 * it operates on the already-assembled page text, so it needs no `libmupdf_java.so` and runs as a
 * real `testDebugUnitTest` (the kind CI runs), unlike every MuPDF type which runs `Context.init()`
 * in a static block. That is also why [DocumentParserFailureTest] has no PDF case.
 */
class PdfEmptinessDetectionTest {

    /** The exact text [PdfParser.parserPdf] assembles for an N-page scanned (no text layer) PDF. */
    private fun markerOnlyText(pages: Int): String = buildString {
        for (i in 0 until pages) {
            append("---")
            append("Page ${i + 1}:\n")
            // page.asText() is empty for an image-only page
            appendLine()
        }
    }

    @Test
    fun markerOnlyPdfText_isEffectivelyEmpty() {
        // The string parserPdf emits for a scanned 2-page PDF: "---Page 1:\n\n---Page 2:\n\n".
        val text = markerOnlyText(2)

        // The bug: the OLD predicate treats this as content. Pin that so the regression is explicit:
        // isBlank() == false means the unfixed parseTyped returned Success for a glyph-less PDF.
        assertFalse(
            "guard: the page markers make the text non-blank — this is exactly why isBlank() failed",
            text.isBlank(),
        )

        // The fix: meaningful-glyph count after stripping markers is 0 => effectively empty => Empty.
        assertTrue(
            "marker-only (scanned) PDF text must be effectively empty, got '${text}'",
            PdfParser.isEffectivelyEmpty(text),
        )
        assertEquals(0, PdfParser.meaningfulGlyphCount(text))
    }

    @Test
    fun meaningfulGlyphCount_stripsMarkersAndWhitespace() {
        // Two pages: page 1 has "Hello world" (10 non-space glyphs), page 2 is whitespace-only.
        val text = "---Page 1:\nHello world\n---Page 2:\n   \n"

        assertEquals(
            "only the real glyphs of 'Helloworld' count; markers and whitespace are excluded",
            10,
            PdfParser.meaningfulGlyphCount(text),
        )
        assertFalse(PdfParser.isEffectivelyEmpty(text))
    }

    @Test
    fun textLayerPdfText_isNotEmpty() {
        // I1 fast-path: a native text-layer PDF keeps a positive glyph count => unaffected => Success.
        val text = "---Page 1:\nThe quick brown fox.\n"

        assertFalse(PdfParser.isEffectivelyEmpty(text))
        assertTrue(PdfParser.meaningfulGlyphCount(text) > 0)
    }

    /**
     * P6 — detection: a document assembled from ONLY marker-only pages (any page count) maps to
     * effectively-empty, never a marker-only "Success".
     */
    @Test
    fun p6_anyNumberOfMarkerOnlyPages_isEffectivelyEmpty() {
        runBlocking {
            checkAll(200, Arb.int(0..40)) { pages ->
                val text = markerOnlyText(pages)
                assertTrue(
                    "a doc of $pages marker-only pages must be effectively empty",
                    PdfParser.isEffectivelyEmpty(text),
                )
            }
        }
    }

    /**
     * P7 — no-noise: the "---Page N:" marker never counts as content, for any page count. The
     * meaningful-glyph count of an all-marker document is always exactly 0.
     */
    @Test
    fun p7_markerNeverCountsAsContent() {
        runBlocking {
            checkAll(200, Arb.int(0..40)) { pages ->
                assertEquals(
                    "markers for $pages pages must contribute 0 meaningful glyphs",
                    0,
                    PdfParser.meaningfulGlyphCount(markerOnlyText(pages)),
                )
            }
        }
    }
}
