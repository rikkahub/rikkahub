package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentTelecomConnectionRouteSelectionTest {
    @Test
    fun `Android 14 endpoint selection admits every Spec A route`() {
        listOf(
            VoiceAgentCallEndpointType.Speaker,
            VoiceAgentCallEndpointType.Earpiece,
            VoiceAgentCallEndpointType.Bluetooth,
            VoiceAgentCallEndpointType.WiredHeadset,
        ).forEach { route ->
            assertTrue(route.name, isSpecAAutomationEndpointRoute(route))
        }
    }

    @Test
    fun `Android 14 endpoint selection rejects routes outside Spec A`() {
        assertFalse(isSpecAAutomationEndpointRoute(VoiceAgentCallEndpointType.Streaming))
        assertFalse(isSpecAAutomationEndpointRoute(VoiceAgentCallEndpointType.Unknown))
    }

    @Test
    fun `available endpoint query returns only provisioned Spec A routes`() {
        val candidates = listOf(
            VoiceAgentCallEndpointCandidate("bt", VoiceAgentCallEndpointType.Bluetooth, "BT"),
            VoiceAgentCallEndpointCandidate("wired", VoiceAgentCallEndpointType.WiredHeadset, "Wired"),
            VoiceAgentCallEndpointCandidate("stream", VoiceAgentCallEndpointType.Streaming, "External"),
        )

        assertEquals(
            setOf(VoiceAgentCallEndpointType.Bluetooth, VoiceAgentCallEndpointType.WiredHeadset),
            availableSpecAAutomationEndpointRoutes(candidates),
        )
    }
}
