package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentStatusLabelTest {
    @Test
    fun `voice launcher label tells the user to start talking`() {
        assertEquals("Start talking", voiceAgentLaunchLabel())
    }

    @Test
    fun `Hermes answered status shows elapsed timing`() {
        assertEquals(
            "Hermes/MS agent answered (call-1, 1234ms)",
            VoiceToolStatus.HermesAnswered(callId = "call-1", elapsedMs = 1234L).visibleStatusLabel(),
        )
    }

    @Test
    fun `Hermes calling status shows elapsed timing when available`() {
        assertEquals(
            "Calling Hermes/MS agent... (call-1, 250ms)",
            VoiceToolStatus.CallingHermes(callId = "call-1", elapsedMs = 250L).visibleStatusLabel(),
        )
    }
}
