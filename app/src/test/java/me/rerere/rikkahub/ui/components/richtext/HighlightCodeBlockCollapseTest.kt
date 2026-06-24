package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the code-block auto-collapse decision and its collapsed preview. The load-bearing fix is the
 * CHARACTER budget: a block that is only a few physical lines but enormous — e.g. a tool result whose
 * pretty-printed JSON holds a whole file as one escaped string — must still collapse. The old
 * line-only check ([COLLAPSE_LINES]) left that single huge line to render and lag the UI.
 */
class HighlightCodeBlockCollapseTest {

    @Test
    fun `collapses when line count exceeds the line budget`() {
        assertTrue(shouldAutoCollapseCode(lineCount = COLLAPSE_LINES + 1, charCount = 10))
        assertFalse(shouldAutoCollapseCode(lineCount = COLLAPSE_LINES, charCount = 10))
    }

    // The regression case: few lines, huge single line. Line-only collapse missed this.
    @Test
    fun `collapses when char count exceeds the char budget even with few lines`() {
        assertTrue(shouldAutoCollapseCode(lineCount = 1, charCount = COLLAPSE_CHARS + 1))
        assertFalse(shouldAutoCollapseCode(lineCount = 1, charCount = COLLAPSE_CHARS))
    }

    @Test
    fun `does not collapse a small block`() {
        assertFalse(shouldAutoCollapseCode(lineCount = 3, charCount = 120))
    }

    // Collapsed preview keeps the first COLLAPSE_LINES lines for an ordinary multi-line block.
    @Test
    fun `collapsed preview keeps the first lines of a multi-line block`() {
        val lines = (1..50).map { "line$it" }
        val preview = collapsedCodePreview(lines)
        assertEquals(lines.take(COLLAPSE_LINES).joinToString("\n"), preview)
    }

    // Collapsed preview caps a single very long line by characters so the whole payload can't slip
    // through the line cap (this is what stops the UI lag on a one-line tool result).
    @Test
    fun `collapsed preview caps a single huge line by characters`() {
        val giant = "x".repeat(COLLAPSE_CHARS * 5)
        val preview = collapsedCodePreview(listOf(giant))
        assertEquals(COLLAPSE_CHARS, preview.length)
    }
}
