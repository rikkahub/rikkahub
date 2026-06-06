package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioDebugInjectorTest {
    @Test
    fun `inject rejects when no capture callback is active`() {
        VoiceAudioDebugInjector.clearForTest()

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )

        assertFalse(result.delivered)
        assertEquals("No active Voice Agent capture session", result.message)
    }

    @Test
    fun `inject delivers ordered chunks to active capture callback`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<List<Byte>>()
        val sleeps = mutableListOf<Long>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk.toList()
        }

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4, 5, 6),
            chunkBytes = 2,
            chunkDelayMs = 7L,
            sleep = sleeps::add,
        )

        assertTrue(result.delivered)
        assertEquals(6, result.bytes)
        assertEquals(3, result.chunkCount)
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5, 6)), chunks)
        assertEquals(listOf(7L, 7L), sleeps)
        registration.close()
    }

    @Test
    fun `closed registration no longer receives injected chunks`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<ByteArray>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk
        }
        registration.close()

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )

        assertFalse(result.delivered)
        assertEquals(emptyList<ByteArray>(), chunks)
    }

    @Test
    fun `inject can wrap pcm with leading and trailing silence`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<List<Byte>>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk.toList()
        }

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4),
            chunkBytes = 8,
            chunkDelayMs = 0L,
            leadingSilenceMs = 1,
            trailingSilenceMs = 1,
        )

        assertTrue(result.delivered)
        assertEquals(68, result.bytes)
        assertEquals(9, result.chunkCount)
        assertEquals(List(32) { 0.toByte() } + listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()) + List(32) { 0.toByte() }, chunks.flatten())
        registration.close()
    }

    @Test
    fun `inject aligns odd chunk size and odd pcm length to pcm16 samples`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<List<Byte>>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk.toList()
        }

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3),
            chunkBytes = 3,
            chunkDelayMs = 0L,
            leadingSilenceMs = 0,
            trailingSilenceMs = 0,
        )

        assertTrue(result.delivered)
        assertEquals(4, result.bytes)
        assertEquals(2, result.chunkCount)
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 0)), chunks)
        registration.close()
    }
}
