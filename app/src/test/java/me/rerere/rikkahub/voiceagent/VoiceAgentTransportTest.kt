package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAgentTransportTest {
    @Test
    fun `transport wire names are stable`() {
        assertEquals("direct_gemini", VoiceAgentTransport.DirectGemini.wireName)
        assertEquals("livekit_experimental", VoiceAgentTransport.LiveKitExperimental.wireName)
        assertEquals("\"direct_gemini\"", Json.encodeToString(VoiceAgentTransport.DirectGemini))
        assertEquals("\"livekit_experimental\"", Json.encodeToString(VoiceAgentTransport.LiveKitExperimental))
        assertEquals(
            VoiceAgentTransport.DirectGemini,
            Json.decodeFromString<VoiceAgentTransport>("\"direct_gemini\""),
        )
        assertEquals(
            VoiceAgentTransport.LiveKitExperimental,
            Json.decodeFromString<VoiceAgentTransport>("\"livekit_experimental\""),
        )
    }

    @Test
    fun `service intent fields preserve transport`() {
        val conversationId = "11111111-1111-4111-8111-111111111111"
        val encoded = encodeVoiceAgentCallStartFields(
            conversationId = conversationId,
            transport = VoiceAgentTransport.LiveKitExperimental,
        )

        val decoded = decodeVoiceAgentCallStartFields(
            conversationId = encoded.conversationId,
            transportWireName = encoded.transportWireName,
            captureFixtureToken = "fixture-17",
        )

        assertEquals(conversationId, decoded?.conversationId?.toString())
        assertEquals(VoiceAgentTransport.LiveKitExperimental, decoded?.transport)
        assertEquals("fixture-17", decoded?.captureFixtureToken)
    }

    @Test
    fun `service intent fields reject missing or invalid transport`() {
        val conversationId = "11111111-1111-4111-8111-111111111111"

        assertNull(decodeVoiceAgentCallStartFields(conversationId, null, null))
        assertNull(decodeVoiceAgentCallStartFields(conversationId, "unknown", null))
        assertNull(decodeVoiceAgentCallStartFields("not-a-uuid", "direct_gemini", null))
        assertNull(decodeVoiceAgentCallStartFields(conversationId, "direct_gemini", " "))
    }
}
