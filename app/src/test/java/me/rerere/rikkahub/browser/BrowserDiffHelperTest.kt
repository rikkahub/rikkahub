package me.rerere.rikkahub.browser

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for [BrowserDiffHelper]. Exercises the four spec-mandated
 * shapes: identical-input → unchanged; empty-before → all-added; truncation cap;
 * multi-line newline handling. The state-changing browser tools' integration tests
 * (the ones that actually wrap [BrowserDiffHelper.computeDiff] under withDiff) live
 * in [BrowserToolsTest] — this file covers the algorithm in isolation.
 */
class BrowserDiffHelperTest {

    @Test fun `identical before and after returns unchanged`() {
        val out = BrowserDiffHelper.computeDiff("hello\nworld", "hello\nworld")
        assertEquals(true, out["unchanged"]?.jsonPrimitive?.booleanOrNull)
        // No added/removed keys when unchanged — keeps the LLM payload minimal.
        assertNull(out["added"])
        assertNull(out["removed"])
    }

    @Test fun `empty before with non-empty after marks everything added`() {
        val out = BrowserDiffHelper.computeDiff("", "first line\nsecond line")
        assertNotNull(out["added"])
        // removed is the empty string, not unchanged
        assertEquals("", out["removed"]?.jsonPrimitive?.contentOrNull)
        assertTrue("added must contain new content",
            out["added"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("first line"))
        assertEquals(false, out["truncated"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun `non-empty before with empty after marks everything removed`() {
        val out = BrowserDiffHelper.computeDiff("first line\nsecond line", "")
        assertNotNull(out["removed"])
        assertEquals("", out["added"]?.jsonPrimitive?.contentOrNull)
        assertTrue("removed must contain old content",
            out["removed"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("first line"))
    }

    @Test fun `multi-line diff distinguishes added from removed`() {
        val before = "alpha\nbeta\ngamma"
        val after = "alpha\nDELTA\ngamma"
        val out = BrowserDiffHelper.computeDiff(before, after)
        val added = out["added"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val removed = out["removed"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("expected DELTA in added, got: $added", added.contains("DELTA"))
        assertTrue("expected beta in removed, got: $removed", removed.contains("beta"))
        // alpha + gamma are unchanged → they MUST NOT appear in either side.
        assertFalse("alpha leaked into added", added.contains("alpha"))
        assertFalse("alpha leaked into removed", removed.contains("alpha"))
    }

    @Test fun `truncation kicks in past 2000 chars per side`() {
        // Build a before of 3000 unique short lines so the diff's added side is well
        // over the per-side cap. Each line is "line-NNNN\n" (~10 chars), 3000 lines →
        // ~30 KB raw — way past the 2000-char per-side cap.
        val after = (1..3000).joinToString("\n") { "line-$it" }
        val out = BrowserDiffHelper.computeDiff("", after)
        val added = out["added"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("added must be capped at MAX_CHARS_PER_SIDE",
            added.length <= BrowserDiffHelper.MAX_CHARS_PER_SIDE)
        assertEquals(true, out["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(added.length, out["added_chars"]?.jsonPrimitive?.intOrNull)
    }

    @Test fun `whitespace-only diff surfaces as unchanged`() {
        // Both sides have content but the only difference is leading/trailing whitespace
        // around lines that are otherwise identical. The diff helper trims the joined
        // result; since neither side ends up with meaningful unique content, we expect
        // the unchanged shortcut.
        val out = BrowserDiffHelper.computeDiff("\n\nhello\n", "\nhello\n\n")
        assertEquals(true, out["unchanged"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun `single-line difference reports correct char counts`() {
        val out = BrowserDiffHelper.computeDiff("foo", "bar")
        // added_chars MUST equal added.length so the LLM doesn't have to re-count.
        val added = out["added"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val removed = out["removed"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertEquals(added.length, out["added_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals(removed.length, out["removed_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals("bar", added)
        assertEquals("foo", removed)
    }
}
