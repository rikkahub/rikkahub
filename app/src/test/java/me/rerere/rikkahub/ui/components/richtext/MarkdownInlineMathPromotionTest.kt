package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInlineMathPromotionTest {
    @Test
    fun `keeps plain long text unchanged`() {
        val text = "This is a long plain text paragraph that should keep wrapping naturally without any inline math segments."
        val promoted = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = emptyList(),
            containerWidthPx = 100f,
            threshold = 0.8f
        )
        assertEquals(text, promoted)
    }

    @Test
    fun `does not promote when ratio below threshold`() {
        val text = "Before $x+1$ after"
        val raw = "$x+1$"
        val start = text.indexOf(raw)
        val segment = InlineMathSegment(
            start = start,
            end = start + raw.length,
            rawText = raw,
            estimatedWidthPx = 79f
        )

        val promoted = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = listOf(segment),
            containerWidthPx = 100f,
            threshold = 0.8f
        )

        assertEquals("Before $x+1$ after", promoted)
    }

    @Test
    fun `promotes when ratio equals threshold`() {
        val text = "Before $x+1$ after"
        val raw = "$x+1$"
        val start = text.indexOf(raw)
        val segment = InlineMathSegment(
            start = start,
            end = start + raw.length,
            rawText = raw,
            estimatedWidthPx = 80f
        )

        val promoted = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = listOf(segment),
            containerWidthPx = 100f,
            threshold = 0.8f
        )

        assertEquals("Before $$x+1$$ after", promoted)
    }

    @Test
    fun `promotes when ratio above threshold`() {
        val text = "Before $x+1$ after"
        val raw = "$x+1$"
        val start = text.indexOf(raw)
        val segment = InlineMathSegment(
            start = start,
            end = start + raw.length,
            rawText = raw,
            estimatedWidthPx = 95f
        )

        val promoted = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = listOf(segment),
            containerWidthPx = 100f,
            threshold = 0.8f
        )

        assertEquals("Before $$x+1$$ after", promoted)
    }

    @Test
    fun `only promotes matched segments and keeps text order`() {
        val text = "A $short$ B $longformula$ C"
        val short = "$short$"
        val long = "$longformula$"
        val shortStart = text.indexOf(short)
        val longStart = text.indexOf(long)
        val segments = listOf(
            InlineMathSegment(
                start = shortStart,
                end = shortStart + short.length,
                rawText = short,
                estimatedWidthPx = 20f
            ),
            InlineMathSegment(
                start = longStart,
                end = longStart + long.length,
                rawText = long,
                estimatedWidthPx = 95f
            )
        )

        val promoted = promoteInlineMathByWidth(
            text = text,
            inlineMathSegments = segments,
            containerWidthPx = 100f,
            threshold = 0.8f
        )

        assertEquals("A $short$ B $$longformula$$ C", promoted)
    }
}
