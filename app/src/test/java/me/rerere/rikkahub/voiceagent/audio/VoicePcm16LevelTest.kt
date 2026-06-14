package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePcm16LevelTest {
    @Test
    fun `pcm16 level reports peak rms and zero crossings`() {
        val level = voicePcm16Level(
            byteArrayOf(
                0x00, 0x00,
                0x00, 0x40,
                0x00, 0x00,
                0x00, 0xC0.toByte(),
            )
        )

        assertEquals(4, level.samples)
        assertEquals(16_384, level.peak)
        assertTrue(level.rms in 11_580..11_585)
        assertEquals(1, level.zeroCrossings)
    }

    @Test
    fun `pcm16 level handles empty and partial samples`() {
        val level = voicePcm16Level(byteArrayOf(0x01))

        assertEquals(0, level.samples)
        assertEquals(0, level.peak)
        assertEquals(0, level.rms)
        assertEquals(0, level.zeroCrossings)
    }
}
