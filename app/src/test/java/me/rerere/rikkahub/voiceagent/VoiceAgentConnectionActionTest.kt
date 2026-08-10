package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentConnectionActionTest {
    @Test
    fun `idle call and session are ready for explicit start`() {
        assertEquals(
            VoiceAgentConnectionPresentation(
                action = VoiceAgentConnectionAction.Start,
                primaryStatus = "Ready to start",
            ),
            VoiceAgentUiState().connectionPresentation(),
        )
    }

    @Test
    fun `every non-idle call state reconnects`() {
        val callStates = listOf(
            VoiceCallStatus.ForegroundStarting,
            VoiceCallStatus.BackgroundCapable,
            VoiceCallStatus.Degraded("test"),
            VoiceCallStatus.Ending,
            VoiceCallStatus.Ended,
        )

        callStates.forEach { call ->
            assertEquals(
                VoiceAgentConnectionAction.Reconnect,
                VoiceAgentUiState(call = call).connectionPresentation().action,
            )
        }
    }

    @Test
    fun `every non-idle session state reconnects`() {
        val sessionStates = listOf(
            VoiceSessionStatus.PreparingContext,
            VoiceSessionStatus.RequestingToken,
            VoiceSessionStatus.ConnectingGemini,
            VoiceSessionStatus.Connected,
            VoiceSessionStatus.Reconnecting,
            VoiceSessionStatus.Ending,
            VoiceSessionStatus.Ended,
            VoiceSessionStatus.Error("test"),
        )

        sessionStates.forEach { session ->
            assertEquals(
                VoiceAgentConnectionAction.Reconnect,
                VoiceAgentUiState(session = session).connectionPresentation().action,
            )
        }
    }
}
