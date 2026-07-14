package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackEventOwnerTest {
    @Test
    fun `forwards events and delegates turn boundary`() {
        val events = mutableListOf<VoicePlaybackEvent>()
        var markedSessionId: Long? = null
        val owner = VoicePlaybackEventOwner()
        owner.setHandler(events::add)

        owner.notify(VoicePlaybackEvent.Active(1L))
        val accepted = owner.markTurnComplete(100L) { sessionId ->
            markedSessionId = sessionId
            true
        }

        assertTrue(accepted)
        assertEquals(100L, markedSessionId)
        assertEquals(listOf(VoicePlaybackEvent.Active(1L)), events)
    }

    @Test
    fun `release retains handler for terminal event then clears it`() {
        val events = mutableListOf<VoicePlaybackEvent>()
        val owner = VoicePlaybackEventOwner()
        owner.setHandler(events::add)

        owner.releasePlayback {
            owner.notify(VoicePlaybackEvent.Drained(1L))
        }
        owner.notify(VoicePlaybackEvent.Active(2L))

        assertEquals(listOf(VoicePlaybackEvent.Drained(1L)), events)
    }
}
