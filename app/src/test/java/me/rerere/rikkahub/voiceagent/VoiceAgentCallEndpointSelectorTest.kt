package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallEndpointSelectorTest {
    @Test
    fun `selects bluetooth endpoint before speaker and earpiece`() {
        val selected = selectPreferredCallEndpoint(
            listOf(
                VoiceAgentCallEndpointCandidate("earpiece", VoiceAgentCallEndpointType.Earpiece, "Phone"),
                VoiceAgentCallEndpointCandidate("speaker", VoiceAgentCallEndpointType.Speaker, "Speaker"),
                VoiceAgentCallEndpointCandidate("bluetooth", VoiceAgentCallEndpointType.Bluetooth, "Headset"),
            )
        )

        assertEquals("bluetooth", selected?.id)
    }

    @Test
    fun `returns null when no bluetooth endpoint is available`() {
        val selected = selectPreferredCallEndpoint(
            listOf(
                VoiceAgentCallEndpointCandidate("earpiece", VoiceAgentCallEndpointType.Earpiece, "Phone"),
                VoiceAgentCallEndpointCandidate("speaker", VoiceAgentCallEndpointType.Speaker, "Speaker"),
            )
        )

        assertNull(selected)
    }

    @Test
    fun `requests bluetooth endpoint when current endpoint is not assigned yet`() {
        assertTrue(
            shouldRequestBluetoothCallEndpoint(
                currentEndpoint = null,
                requestedBluetoothEndpointId = null,
                selectedBluetoothEndpointId = "bluetooth",
            )
        )
    }

    @Test
    fun `does not request bluetooth endpoint that is already current`() {
        assertFalse(
            shouldRequestBluetoothCallEndpoint(
                currentEndpoint = VoiceAgentCurrentCallEndpoint("bluetooth", VoiceAgentCallEndpointType.Bluetooth),
                requestedBluetoothEndpointId = null,
                selectedBluetoothEndpointId = "bluetooth",
            )
        )
    }

    @Test
    fun `does not duplicate pending bluetooth endpoint request`() {
        assertFalse(
            shouldRequestBluetoothCallEndpoint(
                currentEndpoint = VoiceAgentCurrentCallEndpoint("earpiece", VoiceAgentCallEndpointType.Earpiece),
                requestedBluetoothEndpointId = "bluetooth",
                selectedBluetoothEndpointId = "bluetooth",
            )
        )
    }
}
