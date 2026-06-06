package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnosticEvent
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentStateReducerTest {
    @Test
    fun `interruption suppresses playback but leaves tool calls pending`() {
        val initial = VoiceAgentUiState(
            session = VoiceSessionStatus.Connected,
            audio = VoiceAudioStatus.AssistantSpeaking,
            tool = VoiceToolStatus.CallingHermes(callId = "c1", elapsedMs = 1200),
        )

        val next = initial.reduce(VoiceAgentEvent.UserInterrupted)

        assertEquals(VoiceAudioStatus.PlaybackSuppressed, next.audio)
        assertEquals(VoiceToolStatus.CallingHermes(callId = "c1", elapsedMs = 1200), next.tool)
    }

    @Test
    fun `diagnostics keep full payloads for personal alpha`() {
        val diagnostics = VoiceDiagnostics()
        diagnostics.record(
            VoiceDiagnosticEvent(
                name = "tool_call_received",
                detail = """{"prompt":"secret local prompt","token":"visible-in-alpha"}""",
            )
        )

        assertTrue(diagnostics.events.value.single().detail.contains("secret local prompt"))
        assertTrue(diagnostics.events.value.single().detail.contains("visible-in-alpha"))
    }
}
