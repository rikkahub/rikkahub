package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderLimitsTest {

    @Test
    fun `clampTableDimensions clamps oversized values and preserves normal sizes`() {
        val (rows, cols) = clampTableDimensions(rawRows = 1_000, rawCols = 64)
        assertEquals(RenderLimits.MAX_TABLE_ROWS, rows)
        assertEquals(RenderLimits.MAX_TABLE_COLS, cols)

        val (smallRows, smallCols) = clampTableDimensions(rawRows = 20, rawCols = 10)
        assertEquals(20, smallRows)
        assertEquals(10, smallCols)
    }

    @Test
    fun `diffVisibleLines keeps short diffs and reports truncation`() {
        val shortDiff = listOf("a", "b", "c")
        val (shortLines, shortTruncated) = diffVisibleLines(allLines = shortDiff, maxLines = RenderLimits.MAX_DIFF_LINES)
        assertEquals(shortDiff, shortLines)
        assertEquals(0, shortTruncated)

        val longDiff = List(1000) { "line=$it" }
        val (truncatedLines, truncatedCount) = diffVisibleLines(allLines = longDiff, maxLines = RenderLimits.MAX_DIFF_LINES)
        assertEquals(RenderLimits.MAX_DIFF_LINES, truncatedLines.size)
        assertEquals(1000 - RenderLimits.MAX_DIFF_LINES, truncatedCount)
    }

    @Test
    fun `html depth helper caps recursion depth at limit`() {
        assertFalse(shouldStopHtmlDepthRecursion(RenderLimits.MAX_HTML_DEPTH - 1))
        assertTrue(shouldStopHtmlDepthRecursion(RenderLimits.MAX_HTML_DEPTH))
    }
}
