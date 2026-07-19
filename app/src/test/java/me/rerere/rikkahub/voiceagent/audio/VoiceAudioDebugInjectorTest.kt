package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VoiceAudioDebugInjectorTest {
    @Test
    fun `stale delayed registration cannot replace newer active capture`() {
        VoiceAudioDebugInjector.clearForTest()
        val currentOwner = AtomicReference("A")
        val staleReady = CountDownLatch(1)
        val publishStale = CountDownLatch(1)
        val staleChunks = mutableListOf<ByteArray>()
        val currentChunks = mutableListOf<ByteArray>()
        var staleRegistration: VoiceAudioDebugInjector.Registration? = null
        val staleThread = Thread {
            staleReady.countDown()
            publishStale.await()
            staleRegistration = VoiceAudioDebugInjector.registerCaptureIfCurrent(
                onPcm16 = staleChunks::add,
                onInjectionComplete = {},
                isCurrent = { currentOwner.get() == "A" },
            )
        }
        staleThread.start()
        assertTrue(staleReady.await(5, TimeUnit.SECONDS))

        currentOwner.set("B")
        val currentRegistration = VoiceAudioDebugInjector.registerCaptureIfCurrent(
            onPcm16 = currentChunks::add,
            onInjectionComplete = {},
            isCurrent = { currentOwner.get() == "B" },
        )
        publishStale.countDown()
        staleThread.join(5_000)

        assertFalse(staleThread.isAlive)
        assertEquals(null, staleRegistration)
        assertTrue(currentRegistration != null)
        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )
        assertTrue(result.delivered)
        assertEquals(emptyList<ByteArray>(), staleChunks)
        assertEquals(listOf(byteArrayOf(1, 2).toList()), currentChunks.map(ByteArray::toList))
        currentRegistration?.close()
    }

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
    fun `inject notifies active capture when delivered prompt is complete`() {
        VoiceAudioDebugInjector.clearForTest()
        var completionCount = 0
        val registration = VoiceAudioDebugInjector.registerCapture(
            onPcm16 = {},
            onInjectionComplete = { completionCount += 1 },
        )

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )

        assertTrue(result.delivered)
        assertEquals(1, completionCount)
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
