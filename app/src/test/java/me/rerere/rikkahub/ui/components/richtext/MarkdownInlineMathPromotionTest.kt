package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInlineMathPromotionTest {
    @Test
    fun keepTextWhenNoInlineMath() {
        val text = "plain text only"

        val rewritten = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = emptyList(),
            containerWidthPx = 100f,
        )

        assertEquals(text, rewritten)
    }

    @Test
    fun promoteWhenMathWidthGreaterThanContainer() {
        val text = "a \$x\$ b"
        val segments = listOf(
            InlineMathSegment(
                start = 2,
                end = 5,
                rawText = "\$x\$",
                estimatedWidthPx = 101f,
            )
        )

        val rewritten = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = segments,
            containerWidthPx = 100f,
        )

        assertEquals("a \$\$x\$\$ b", rewritten)
    }

    @Test
    fun keepWhenMathWidthEqualsContainer() {
        val text = "a \$x\$ b"
        val segments = listOf(
            InlineMathSegment(
                start = 2,
                end = 5,
                rawText = "\$x\$",
                estimatedWidthPx = 100f,
            )
        )

        val rewritten = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = segments,
            containerWidthPx = 100f,
        )

        assertEquals(text, rewritten)
    }

    @Test
    fun promoteOnlyOverflowingMathSegments() {
        val text = "a \$x\$ b \$y\$ c"
        val segments = listOf(
            InlineMathSegment(
                start = 2,
                end = 5,
                rawText = "\$x\$",
                estimatedWidthPx = 120f,
            ),
            InlineMathSegment(
                start = 8,
                end = 11,
                rawText = "\$y\$",
                estimatedWidthPx = 80f,
            )
        )

        val rewritten = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = segments,
            containerWidthPx = 100f,
        )

        assertEquals("a \$\$x\$\$ b \$y\$ c", rewritten)
    }

    @Test
    fun doNotRewriteExistingBlockMathDelimiter() {
        assertEquals("\$\$x\$\$", inlineToBlockMath("\$\$x\$\$"))
    }
}
