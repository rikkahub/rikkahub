package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProcessingStatusTest {
    @Test
    fun initialEmptyStatusDoesNotCreatePresentation() {
        val presentation = agentProcessingStatusPresentation(
            currentStatus = null,
            retainedStatus = null,
        )

        assertFalse(presentation.visible)
        assertNull(presentation.displayedText)
        assertNull(presentation.retainedText)
    }

    @Test
    fun currentStatusBecomesVisibleAndReplacesRetainedPhase() {
        val first = agentProcessingStatusPresentation(
            currentStatus = "Routing request",
            retainedStatus = null,
        )
        val second = agentProcessingStatusPresentation(
            currentStatus = "Running tool",
            retainedStatus = first.retainedText,
        )

        assertTrue(first.visible)
        assertEquals("Routing request", first.displayedText)
        assertEquals("Running tool", second.displayedText)
        assertEquals("Running tool", second.retainedText)
    }

    @Test
    fun clearedStatusKeepsOutgoingTextForExitMotion() {
        val presentation = agentProcessingStatusPresentation(
            currentStatus = null,
            retainedStatus = "Running tool",
        )

        assertFalse(presentation.visible)
        assertEquals("Running tool", presentation.displayedText)
        assertEquals("Running tool", presentation.retainedText)
    }

    @Test
    fun blankStatusBehavesLikeClearWithoutErasingRetainedText() {
        val presentation = agentProcessingStatusPresentation(
            currentStatus = "   ",
            retainedStatus = "Preparing context",
        )

        assertFalse(presentation.visible)
        assertEquals("Preparing context", presentation.displayedText)
    }
}
