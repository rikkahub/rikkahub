package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentStartGateTest {
    @Test
    fun `is ready when microphone permission is granted`() {
        assertEquals(
            VoiceAgentStartGate.Ready,
            voiceAgentStartGate(hasMicrophonePermission = true),
        )
    }

    @Test
    fun `requires microphone permission before starting`() {
        assertEquals(
            VoiceAgentStartGate.NeedsMicrophonePermission,
            voiceAgentStartGate(hasMicrophonePermission = false),
        )
    }
}
