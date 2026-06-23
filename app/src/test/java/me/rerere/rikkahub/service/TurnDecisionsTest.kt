package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure turn pre-flight decisions (#360 P6) extracted from
 * `ChatService.handleMessageComplete`: the sender-name fallback, the tool-unavailable warning
 * predicate (with its lazy short-circuit), and the message-range slice (with its inclusive-end
 * boundary). None of these were unit-testable while inlined in the turn orchestration.
 */
class TurnDecisionsTest {

    // --- resolveSenderName -------------------------------------------------------------------

    @Test
    fun `sender name is the model display name when the assistant avatar is not used`() {
        var defaultCalled = false
        val name = resolveSenderName(
            useAssistantAvatar = false,
            assistantName = "Ada",
            modelDisplayName = "GPT",
        ) { defaultCalled = true; "Assistant" }
        assertEquals("GPT", name)
        assertFalse("the fallback must not be resolved when the avatar is unused", defaultCalled)
    }

    @Test
    fun `sender name is the assistant name when the avatar is used and the name is set`() {
        var defaultCalled = false
        val name = resolveSenderName(
            useAssistantAvatar = true,
            assistantName = "Ada",
            modelDisplayName = "GPT",
        ) { defaultCalled = true; "Assistant" }
        assertEquals("Ada", name)
        assertFalse("the fallback must not be resolved when the name is non-empty", defaultCalled)
    }

    @Test
    fun `sender name falls back to the default only when the avatar is used and the name is blank`() {
        var defaultCalled = false
        val name = resolveSenderName(
            useAssistantAvatar = true,
            assistantName = "",
            modelDisplayName = "GPT",
        ) { defaultCalled = true; "Assistant" }
        assertEquals("Assistant", name)
        assertTrue("the fallback is resolved lazily only on the empty-name path", defaultCalled)
    }

    // --- shouldWarnToolUnavailable ----------------------------------------------------------

    @Test
    fun `no warning when the model supports tools, and the mcp probe is never run`() = runBlocking {
        var probed = false
        val warn = shouldWarnToolUnavailable(
            modelSupportsTools = true,
            webSearchEnabled = true,
        ) { probed = true; true }
        assertFalse(warn)
        assertFalse("the mcp probe must short-circuit when the model supports tools", probed)
    }

    @Test
    fun `warns when the model lacks tools and web search is on, without probing mcp`() = runBlocking {
        var probed = false
        val warn = shouldWarnToolUnavailable(
            modelSupportsTools = false,
            webSearchEnabled = true,
        ) { probed = true; false }
        assertTrue(warn)
        assertFalse("web-search-on decides the warning, so the mcp probe is skipped", probed)
    }

    @Test
    fun `probes mcp only when the model lacks tools and web search is off`() = runBlocking {
        var probedWhenMcpPresent = false
        val warnWithMcp = shouldWarnToolUnavailable(
            modelSupportsTools = false,
            webSearchEnabled = false,
        ) { probedWhenMcpPresent = true; true }
        assertTrue("an mcp tool present => warn", warnWithMcp)
        assertTrue(probedWhenMcpPresent)

        val warnWithoutMcp = shouldWarnToolUnavailable(
            modelSupportsTools = false,
            webSearchEnabled = false,
        ) { false }
        assertFalse("no tools at all => no warning", warnWithoutMcp)
    }

    // --- sliceTurnMessages ------------------------------------------------------------------

    @Test
    fun `a null range returns the whole list`() {
        val all = listOf("a", "b", "c", "d")
        assertEquals(all, sliceTurnMessages(all, null))
    }

    @Test
    fun `an inclusive range slices end-inclusive`() {
        val all = listOf("a", "b", "c", "d")
        assertEquals("indices 1..2 inclusive => b, c", listOf("b", "c"), sliceTurnMessages(all, 1..2))
    }

    @Test
    fun `a single-element inclusive range returns exactly that element`() {
        val all = listOf("a", "b", "c", "d")
        assertEquals("0..0 must be one element, not empty (endInclusive + 1)", listOf("a"), sliceTurnMessages(all, 0..0))
    }

    @Test
    fun `the full inclusive range returns every element`() {
        val all = listOf("a", "b", "c", "d")
        assertEquals(all, sliceTurnMessages(all, 0..(all.size - 1)))
    }
}
