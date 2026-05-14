package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the JS predicate browser_wait_for polls. The predicate is a pure string builder
 * so it's unit-testable without a WebView. We assert on structural properties of the emitted
 * JS rather than running it — the goal is to catch a regression that changes what condition
 * the poll loop waits on (e.g. dropping the text constraint, or swapping attached/detached).
 */
class BrowserWaitForPredicateTest {

    @Test
    fun `default state attached without text checks for any matching element`() {
        val js = buildWaitForPredicate(".foo", "attached", null)
        assertTrue("queries the selector", js.contains("querySelectorAll"))
        assertTrue("selector is JSON-encoded into the payload", js.contains("\".foo\""))
        // No contains_text → the text check is the always-true stub.
        assertTrue("text check is a no-op", js.contains("return true;}"))
    }

    @Test
    fun `detached state checks for absence`() {
        val js = buildWaitForPredicate("#x", "detached", null)
        assertTrue("uses querySelector null check", js.contains("querySelector(\"#x\")===null"))
    }

    @Test
    fun `visible state checks rendering and ignores nothing when text absent`() {
        val js = buildWaitForPredicate(".btn", "visible", null)
        assertTrue("checks offsetParent", js.contains("offsetParent"))
        assertTrue("checks client rects", js.contains("getClientRects"))
        // visible requires at least one element to pass → returns false by default.
        assertTrue("defaults to false when nothing visible", js.contains("return false;"))
    }

    @Test
    fun `hidden state passes when no element is visible`() {
        val js = buildWaitForPredicate(".spinner", "hidden", null)
        assertTrue("iterates matches", js.contains("querySelectorAll"))
        assertTrue("returns false if any visible", js.contains("return false;"))
        assertTrue("returns true when none visible", js.contains("return true;"))
    }

    @Test
    fun `contains_text adds a substring constraint on attached`() {
        val js = buildWaitForPredicate("h1", "attached", "Welcome")
        assertTrue("text is JSON-encoded into the payload", js.contains("\"Welcome\""))
        assertTrue("uses indexOf substring match", js.contains("indexOf(\"Welcome\")"))
    }

    @Test
    fun `contains_text combines with visible state`() {
        val js = buildWaitForPredicate(".toast", "visible", "Saved")
        assertTrue("checks visibility", js.contains("offsetParent"))
        assertTrue("checks text", js.contains("indexOf(\"Saved\")"))
    }

    @Test
    fun `contains_text is ignored for detached state`() {
        // "not in the DOM" can't also "contain text" — detached must not splice the text in.
        val js = buildWaitForPredicate(".gone", "detached", "anything")
        assertFalse("detached predicate does not reference the text", js.contains("anything"))
    }

    @Test
    fun `selector with quotes is safely escaped`() {
        // jsString routes through JsonPrimitive — a selector with a double quote must not
        // break out of the string literal.
        val js = buildWaitForPredicate("a[title=\"x\"]", "attached", null)
        assertFalse("no raw unescaped quote sequence", js.contains("=\"x\"]"))
        assertTrue("quote is escaped", js.contains("\\\""))
    }
}
