package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression guard for issue #209 split-path inline-math color staleness.
 *
 * The split inline-formula path bakes the text color into each drawable at build time and
 * caches its InlineTextContent in a remembered, never-cleared map keyed by [latexSegmentKey].
 * On a live theme toggle the color changes and the drawable is rebuilt, but the map's
 * putIfAbsent only re-renders if the KEY also changes. A color-independent key
 * ("latex:${formula.hashCode()}:$index") would no-op and keep the stale-colored glyph.
 *
 * These are pure-JVM tests of that invariant: the actual staleness is a Compose
 * recomposition behaviour that needs a device to observe, so the property that must hold is
 * structural — the key distinguishes color. Each `different color -> different key` assertion
 * FAILS against the pre-fix color-independent key and PASSES once color is part of the key.
 */
class LatexTextTest {

    private val light = 0xFF000000.toInt()
    private val dark = 0xFFFFFFFF.toInt()

    @Test
    fun `different color yields different key for same formula and index`() {
        assertNotEquals(
            latexSegmentKey(dark, "a+b", 0),
            latexSegmentKey(light, "a+b", 0),
        )
    }

    @Test
    fun `single-segment operator-free formula still re-keys on color change`() {
        // JLatexMathSplitter returns a singletonList for operator-free formulas, which still
        // flows through the split branch as index 0 -- the staleness must be fixed there too.
        assertNotEquals(
            latexSegmentKey(dark, "x", 0),
            latexSegmentKey(light, "x", 0),
        )
    }

    @Test
    fun `same inputs are deterministic`() {
        assertEquals(
            latexSegmentKey(light, "a+b", 1),
            latexSegmentKey(light, "a+b", 1),
        )
    }

    @Test
    fun `different segment index yields different key`() {
        assertNotEquals(
            latexSegmentKey(light, "a+b+c", 0),
            latexSegmentKey(light, "a+b+c", 1),
        )
    }

    @Test
    fun `toggling theme back reuses the original key`() {
        // Metamorphic: light -> dark -> light returns the original key so a re-render after
        // toggling back hits the cache instead of leaking an unbounded set of stale entries.
        val original = latexSegmentKey(light, "a+b", 0)
        val toggled = latexSegmentKey(dark, "a+b", 0)
        val back = latexSegmentKey(light, "a+b", 0)
        assertNotEquals(original, toggled)
        assertEquals(original, back)
    }
}
