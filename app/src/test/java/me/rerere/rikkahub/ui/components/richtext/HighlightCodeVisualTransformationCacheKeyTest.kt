package me.rerere.rikkahub.ui.components.richtext

import android.content.ContextWrapper
import me.rerere.highlight.Highlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoreTextField caches the transformed text with
 * `remember(value, visualTransformation)` (foundation CoreTextField.kt:246), and
 * `remember` compares keys with `equals`. Every call site builds
 * `HighlightCodeVisualTransformation` inline in composition, so the transformation
 * must compare equal across recompositions — otherwise the cache misses on every
 * recomposition and `filter()` re-runs the runBlocking highlight for unchanged text
 * (measured at 0.31–7.08 ms JVM-side decode per call in HighlightLatencyBenchTest).
 */
class HighlightCodeVisualTransformationCacheKeyTest {
    private val highlighter = Highlighter(ContextWrapper(null))

    @Test
    fun `same configuration compares equal across instances`() {
        val first = HighlightCodeVisualTransformation("json", highlighter, darkMode = true)
        val second = HighlightCodeVisualTransformation("json", highlighter, darkMode = true)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `different language does not compare equal`() {
        val json = HighlightCodeVisualTransformation("json", highlighter, darkMode = true)
        val javascript = HighlightCodeVisualTransformation("javascript", highlighter, darkMode = true)

        assertNotEquals(json, javascript)
    }

    @Test
    fun `different dark mode does not compare equal`() {
        val dark = HighlightCodeVisualTransformation("json", highlighter, darkMode = true)
        val light = HighlightCodeVisualTransformation("json", highlighter, darkMode = false)

        assertNotEquals(dark, light)
    }

    @Test
    fun `different highlighter instance does not compare equal`() {
        val first = HighlightCodeVisualTransformation("json", highlighter, darkMode = true)
        val second = HighlightCodeVisualTransformation("json", Highlighter(ContextWrapper(null)), darkMode = true)

        assertNotEquals(first, second)
    }

    @Test
    fun `redundant recomposition key check costs nanoseconds not milliseconds`() {
        val first = HighlightCodeVisualTransformation("json", highlighter, darkMode = true)
        val second = HighlightCodeVisualTransformation("json", highlighter, darkMode = true)

        var sink = 0
        repeat(WARMUP_CALLS) { if (first == second) sink++ }
        val start = System.nanoTime()
        repeat(MEASURED_CALLS) { if (first == second) sink++ }
        val nsPerEquals = (System.nanoTime() - start).toDouble() / MEASURED_CALLS

        println("[BENCH highlight cacheKey] nsPerEquals=${"%.1f".format(nsPerEquals)}")
        assertEquals(WARMUP_CALLS + MEASURED_CALLS, sink)
        assertTrue("harness must have timed real work", nsPerEquals > 0.0)
    }

    companion object {
        private const val WARMUP_CALLS = 100_000
        private const val MEASURED_CALLS = 1_000_000
    }
}
