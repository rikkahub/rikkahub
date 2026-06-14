package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAudioRouteSelectorTest {
    @Test
    fun `selects bluetooth sco input before built in mic`() {
        val selected = selectPreferredCaptureRoute(
            listOf(
                VoiceAudioRouteDevice(id = 1, type = VoiceAudioRouteDeviceType.BuiltInMic, name = "phone"),
                VoiceAudioRouteDevice(id = 2, type = VoiceAudioRouteDeviceType.BluetoothSco, name = "headset"),
            )
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `falls back to null when no bluetooth capture route is available`() {
        val selected = selectPreferredCaptureRoute(
            listOf(
                VoiceAudioRouteDevice(id = 1, type = VoiceAudioRouteDeviceType.BuiltInMic, name = "phone"),
                VoiceAudioRouteDevice(id = 3, type = VoiceAudioRouteDeviceType.WiredHeadset, name = "wired"),
            )
        )

        assertNull(selected)
    }
}
