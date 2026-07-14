package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioTrackRetirementTest {
    @Test
    fun `failed flush and release propagate without removing track ownership`() {
        val operations = mutableListOf<String>()
        var removals = 0
        val retirement = AudioTrackRetirement(
            pause = { operations += "pause"; true },
            flush = { operations += "flush"; false },
            stop = { operations += "stop"; true },
            release = { operations += "release"; false },
            removeTrack = { removals += 1 },
        )

        assertThrows(IllegalStateException::class.java) {
            retirement.pauseAndFlush()
        }
        assertThrows(IllegalStateException::class.java) {
            retirement.stopAndRelease()
        }

        assertEquals(listOf("pause", "flush", "stop", "release"), operations)
        assertEquals(0, removals)
    }

    @Test
    fun `confirmed flush or release completes its retirement route`() {
        var removals = 0
        val retirement = AudioTrackRetirement(
            pause = { true },
            flush = { true },
            stop = { false },
            release = { true },
            removeTrack = { removals += 1 },
        )

        retirement.pauseAndFlush()
        retirement.stopAndRelease()

        assertEquals(1, removals)
    }
}
