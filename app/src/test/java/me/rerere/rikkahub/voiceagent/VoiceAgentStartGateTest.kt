package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentStartGateTest {
    @Test
    fun `is ready when microphone permission is granted`() {
        assertEquals(
            VoiceAgentStartGate.Ready,
            voiceAgentStartGate(
                hasMicrophonePermission = true,
                hasNotificationPermission = true,
            ),
        )
    }

    @Test
    fun `requires microphone permission before starting`() {
        assertEquals(
            VoiceAgentStartGate.NeedsMicrophonePermission,
            voiceAgentStartGate(
                hasMicrophonePermission = false,
                hasNotificationPermission = true,
            ),
        )
    }

    @Test
    fun `start gate requires notification permission after microphone permission`() {
        assertEquals(
            VoiceAgentStartGate.NeedsNotificationPermission,
            voiceAgentStartGate(
                hasMicrophonePermission = true,
                hasNotificationPermission = false,
            ),
        )
    }

    @Test
    fun `start gate is ready when microphone and notification are granted`() {
        assertEquals(
            VoiceAgentStartGate.Ready,
            voiceAgentStartGate(
                hasMicrophonePermission = true,
                hasNotificationPermission = true,
            ),
        )
    }
}
