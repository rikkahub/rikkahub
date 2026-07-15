package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackIdentityTest {
    @Test
    fun `voice playback events expose a playback epoch`() {
        val epoch = PlaybackEpoch(7L)
        val event: VoicePlaybackEvent = VoicePlaybackEvent.Active(epoch)
        assertEquals(epoch, event.playbackEpoch)
        assertTrue(PlaybackEpoch(7L) < PlaybackEpoch(8L))
    }

    @Test
    fun `writer generation remains a separate comparable type`() {
        assertTrue(WriterGeneration(3L) < WriterGeneration(4L))
        assertEquals(4L, WriterGeneration(4L).value)
    }
}
