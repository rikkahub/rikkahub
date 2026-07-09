package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSuppressionControllerTest {

    @Test
    fun `suppress sets suppressed and deactivates assistant audio`() {
        val activeChanges = mutableListOf<Boolean>()
        val controller = controller(onAssistantAudioActiveChanged = { activeChanges += it })

        controller.suppress()

        assertTrue(controller.isSuppressed())
        assertEquals(listOf(false), activeChanges)
    }

    @Test
    fun `clearForNewTurn clears both flags`() {
        val controller = controller()
        controller.suppress()
        controller.markInterruptedByGemini()

        controller.clearForNewTurn()

        assertFalse(controller.isSuppressed())
        // Both flags cleared: a subsequent interruption-triggered clear finds nothing left
        // to consume/clear (suppressed already false), i.e. it returns false.
        assertFalse(controller.clearForAssistantTurnAfterInterruption())
    }

    @Test
    fun `clearForAssistantTurnAfterInterruption clears only when both flags set, always consumes the interruption flag`() {
        // Row 1: suppressed + interrupted -> true, both cleared.
        val bothSet = controller()
        bothSet.suppress()
        bothSet.markInterruptedByGemini()
        assertTrue(bothSet.clearForAssistantTurnAfterInterruption())
        assertFalse(bothSet.isSuppressed())
        // Interruption flag was consumed too: calling again with suppressed still false
        // stays false (nothing to clear).
        assertFalse(bothSet.clearForAssistantTurnAfterInterruption())

        // Row 2: suppressed only -> false, suppression kept.
        val suppressedOnly = controller()
        suppressedOnly.suppress()
        assertFalse(suppressedOnly.clearForAssistantTurnAfterInterruption())
        assertTrue(suppressedOnly.isSuppressed())

        // Row 3: interrupted only -> false, interruption flag cleared (no suppression to clear).
        val interruptedOnly = controller()
        interruptedOnly.markInterruptedByGemini()
        assertFalse(interruptedOnly.clearForAssistantTurnAfterInterruption())
        assertFalse(interruptedOnly.isSuppressed())
    }

    @Test
    fun `tryActivatePlayback suppressed diagnostic, stale diagnostic, otherwise activates audio`() {
        val activeChanges = mutableListOf<Boolean>()
        val suppressedController = controller(onAssistantAudioActiveChanged = { activeChanges += it })
        suppressedController.suppress()
        assertEquals(
            "output_audio_state_suppressed_after_interruption",
            suppressedController.tryActivatePlayback(isStale = { false }),
        )

        val staleController = controller()
        assertEquals(
            "stale_output_audio_state_suppressed",
            staleController.tryActivatePlayback(isStale = { true }),
        )

        val activatingController = controller(onAssistantAudioActiveChanged = { activeChanges += it })
        assertNull(activatingController.tryActivatePlayback(isStale = { false }))
        assertTrue(activeChanges.contains(true))
    }

    @Test
    fun `clearSuppressionOnly leaves the interruption flag intact`() {
        val controller = controller()
        controller.suppress()
        controller.markInterruptedByGemini()

        controller.clearSuppressionOnly()

        assertFalse(controller.isSuppressed())
        // The interruption flag is still set, but suppressed is already false, so this
        // returns false — and the interruption flag is consumed by the call.
        assertFalse(controller.clearForAssistantTurnAfterInterruption())
    }

    private fun controller(
        onAssistantAudioActiveChanged: (Boolean) -> Unit = {},
    ): PlaybackSuppressionController = PlaybackSuppressionController(
        onAssistantAudioActiveChanged = onAssistantAudioActiveChanged,
    )
}
