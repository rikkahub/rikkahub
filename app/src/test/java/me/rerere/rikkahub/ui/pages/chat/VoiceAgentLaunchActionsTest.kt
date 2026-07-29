package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.decodeVoiceAgentTransport
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentLaunchActionsTest {
    @Test
    fun `experimental action is absent by default and direct remains top bar`() {
        val actions = launchActions(experimentEnabled = false)

        assertEquals(listOf("Start talking"), actions.map { it.label })
        assertEquals(VoiceAgentTransport.DirectGemini, actions.single().transport)
        assertFalse(actions.single().inOptions)
    }

    @Test
    fun `experiment action appears only in options when enabled`() {
        val actions = launchActions(experimentEnabled = true)

        assertEquals(
            listOf("Start talking", "Voice Agent via LiveKit (Experimental)"),
            actions.map { it.label },
        )
        assertEquals(VoiceAgentTransport.DirectGemini, actions.single { !it.inOptions }.transport)
        assertEquals(
            VoiceAgentTransport.LiveKitExperimental,
            actions.single { it.inOptions }.transport,
        )
    }

    @Test
    fun `launch actions preserve transport in navigation`() {
        val direct = voiceAgentScreen("conversation-1", launchActions(false).single())
        val experimental = voiceAgentScreen(
            "conversation-1",
            launchActions(true).single { it.inOptions },
        )

        assertEquals("direct_gemini", direct.transportWireName)
        assertEquals("livekit_experimental", experimental.transportWireName)
        assertEquals(VoiceAgentTransport.DirectGemini, decodeVoiceAgentTransport(direct.transportWireName))
        assertEquals(
            VoiceAgentTransport.LiveKitExperimental,
            decodeVoiceAgentTransport(experimental.transportWireName),
        )
    }

    @Test
    fun `voice agent navigation rejects missing and unknown transports`() {
        assertThrows(IllegalArgumentException::class.java) { decodeVoiceAgentTransport(null) }
        assertThrows(IllegalArgumentException::class.java) { decodeVoiceAgentTransport("") }
        assertThrows(IllegalArgumentException::class.java) { decodeVoiceAgentTransport("unknown") }
    }

    @Test
    fun `voice agent screen serializes transport for navigation round trip`() {
        val screen = Screen.VoiceAgent(
            conversationId = "conversation-1",
            transportWireName = VoiceAgentTransport.LiveKitExperimental.wireName,
        )

        val encoded = Json.encodeToString(screen)
        val decoded = Json.decodeFromString<Screen.VoiceAgent>(encoded)

        assertEquals(screen, decoded)
        assertTrue(encoded.contains("\"transportWireName\":\"livekit_experimental\""))
    }

    @Test
    fun `legacy voice agent navigation defaults to direct Gemini`() {
        val decoded = Json.decodeFromString<Screen.VoiceAgent>(
            """{"conversationId":"legacy-conversation"}""",
        )

        assertEquals("legacy-conversation", decoded.conversationId)
        assertEquals(VoiceAgentTransport.DirectGemini.wireName, decoded.transportWireName)
        assertEquals(
            VoiceAgentTransport.DirectGemini,
            decodeVoiceAgentTransport(decoded.transportWireName),
        )
    }
}
