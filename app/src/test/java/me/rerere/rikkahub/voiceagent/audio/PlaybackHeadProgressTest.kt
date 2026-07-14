package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHeadProgressTest {
    @Test
    fun `counts frames advanced from the starting playback head`() {
        val progress = PlaybackHeadProgress(startRawPosition = 100)

        assertEquals(0L, progress.framesPlayed(rawPosition = 100))
        assertEquals(25L, progress.framesPlayed(rawPosition = 125))
        assertEquals(80L, progress.framesPlayed(rawPosition = 180))
    }

    @Test
    fun `counts across unsigned 32 bit playback head wrap`() {
        val progress = PlaybackHeadProgress(startRawPosition = -6)

        assertEquals(4L, progress.framesPlayed(rawPosition = -2))
        assertEquals(8L, progress.framesPlayed(rawPosition = 2))
    }
}
