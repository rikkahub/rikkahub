package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureFixtureSourceTest {
    @After
    fun tearDown() {
        VoiceCaptureFixtureArming.clearForTest()
    }

    @Test
    fun `armed fixture source pumps initial chunks in order and completes once`() = runTest {
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("prompt.pcm", byteArrayOf(1, 2, 3, 4, 5, 6), chunkBytes = 2),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        val chunks = mutableListOf<List<Byte>>()
        var completions = 0

        val pump = async {
            source.pump(
                onPcm16 = { chunks += it.toList() },
                onFixtureComplete = { completions += 1 },
            )
        }
        source.startInitial()
        source.awaitIdle()

        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5, 6)), chunks)
        assertEquals(1, completions)

        source.close()
        pump.await()
    }

    @Test
    fun `fixture trigger requires the exact active owner and only admits a staged fixture once`() = runTest {
        val staged = fixture("interrupt.pcm", byteArrayOf(7, 8), chunkBytes = 2)
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("prompt.pcm", byteArrayOf(1, 2), chunkBytes = 2),
            staged = listOf(staged),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        val chunks = mutableListOf<List<Byte>>()
        val pump = async {
            source.pump(
                onPcm16 = { chunks += it.toList() },
                onFixtureComplete = {},
            )
        }
        source.startInitial()
        source.awaitIdle()

        assertFalse(VoiceCaptureFixtureArming.trigger("fixture-stale", staged.path).accepted)
        assertTrue(VoiceCaptureFixtureArming.trigger(token, staged.path).accepted)
        source.awaitIdle()
        assertFalse(VoiceCaptureFixtureArming.trigger(token, staged.path).accepted)
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(7, 8)), chunks)

        source.close()
        pump.await()
    }

    @Test
    fun `active owner stages and consumes several distinct fixtures`() = runTest {
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(1, 2), chunkBytes = 2),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        val firstBytes = byteArrayOf(3, 4)
        val first = fixture("request-2.pcm", firstBytes, chunkBytes = 2)
        val second = fixture("follow-up.pcm", byteArrayOf(5, 6), chunkBytes = 2)
        val chunks = mutableListOf<List<Byte>>()
        val pump = async {
            source.pump(
                onPcm16 = { chunks += it.toList() },
                onFixtureComplete = {},
            )
        }

        assertTrue(VoiceCaptureFixtureArming.stage(token, first).accepted)
        assertTrue(VoiceCaptureFixtureArming.stage(token, second).accepted)
        assertFalse(VoiceCaptureFixtureArming.stage("fixture-stale", first).accepted)
        firstBytes.fill(99)

        assertTrue(VoiceCaptureFixtureArming.trigger(token, first.path).accepted)
        source.awaitIdle()
        assertTrue(VoiceCaptureFixtureArming.trigger(token, second.path).accepted)
        source.awaitIdle()
        assertEquals(listOf(listOf<Byte>(3, 4), listOf<Byte>(5, 6)), chunks)

        source.close()
        pump.await()
    }

    @Test
    fun `staging rejects duplicate and queued fixture paths`() = runTest {
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(1, 2), chunkBytes = 2),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        val fixture = fixture("request-2.pcm", byteArrayOf(3, 4), chunkBytes = 2)

        assertTrue(VoiceCaptureFixtureArming.stage(token, fixture).accepted)
        assertFalse(VoiceCaptureFixtureArming.stage(token, fixture).accepted)
        assertTrue(VoiceCaptureFixtureArming.trigger(token, fixture.path).accepted)
        assertFalse(
            VoiceCaptureFixtureArming.stage(
                token,
                fixture("request-2.pcm", byteArrayOf(9, 10), chunkBytes = 2),
            ).accepted,
        )

        source.close()
    }

    @Test
    fun `closed source rejects fixture staging`() = runTest {
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("initial.pcm", byteArrayOf(1, 2), chunkBytes = 2),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        source.close()

        assertFalse(
            source.stage(
                fixture("request-2.pcm", byteArrayOf(3, 4), chunkBytes = 2),
            ).accepted,
        )
    }

    @Test
    fun `cancelled stale pump cannot complete or clear a newer consumer`() = runTest {
        val firstChunkDelivered = CompletableDeferred<Unit>()
        val allowDelay = CompletableDeferred<Unit>()
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("prompt.pcm", byteArrayOf(1, 2, 3, 4), chunkBytes = 2),
            staged = listOf(fixture("next.pcm", byteArrayOf(5, 6), chunkBytes = 2)),
        )
        val source = VoiceCaptureFixtureArming.claim(token) {
            firstChunkDelivered.complete(Unit)
            allowDelay.await()
        }.getOrThrow()
        val staleChunks = mutableListOf<List<Byte>>()
        var staleCompletions = 0
        val stalePump = async {
            source.pump(
                onPcm16 = { staleChunks += it.toList() },
                onFixtureComplete = { staleCompletions += 1 },
            )
        }
        source.startInitial()
        firstChunkDelivered.await()

        val currentChunks = mutableListOf<List<Byte>>()
        val currentPump = async {
            source.pump(
                onPcm16 = { currentChunks += it.toList() },
                onFixtureComplete = {},
            )
        }
        stalePump.cancelAndJoin()
        allowDelay.complete(Unit)
        assertTrue(VoiceCaptureFixtureArming.trigger(token, "next.pcm").accepted)
        source.awaitIdle()

        assertEquals(listOf(listOf<Byte>(1, 2)), staleChunks)
        assertEquals(0, staleCompletions)
        assertEquals(listOf(listOf<Byte>(5, 6)), currentChunks)

        source.close()
        currentPump.await()
    }

    @Test
    fun `stale close cannot clear a newer armed fixture owner`() = runTest {
        val oldToken = VoiceCaptureFixtureArming.arm(
            initial = fixture("old.pcm", byteArrayOf(1, 2), chunkBytes = 2),
            staged = emptyList(),
        )
        val oldSource = VoiceCaptureFixtureArming.claim(oldToken, delays = {}).getOrThrow()
        oldSource.close()

        val newFixture = fixture("new-next.pcm", byteArrayOf(9, 10), chunkBytes = 2)
        val newToken = VoiceCaptureFixtureArming.arm(
            initial = fixture("new.pcm", byteArrayOf(3, 4), chunkBytes = 2),
            staged = listOf(newFixture),
        )
        val newSource = VoiceCaptureFixtureArming.claim(newToken, delays = {}).getOrThrow()

        oldSource.close()

        assertTrue(VoiceCaptureFixtureArming.trigger(newToken, newFixture.path).accepted)
        newSource.close()
    }

    @Test
    fun `failed fixture pump releases its exact source owner`() = runTest {
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("failed.pcm", byteArrayOf(1, 2), chunkBytes = 2),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()

        source.startInitial()
        val failure = runCatching {
            source.pump(
                onPcm16 = { error("transport callback failed") },
                onFixtureComplete = {},
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val successorToken = VoiceCaptureFixtureArming.arm(
            initial = fixture("successor.pcm", byteArrayOf(3, 4), chunkBytes = 2),
            staged = emptyList(),
        )
        VoiceCaptureFixtureArming.claim(successorToken, delays = {}).getOrThrow().close()
    }

    @Test
    fun `fixture selection never constructs a microphone recorder`() = runTest {
        val token = VoiceCaptureFixtureArming.arm(
            initial = fixture("prompt.pcm", byteArrayOf(1, 2), chunkBytes = 2),
            staged = emptyList(),
        )
        val source = VoiceCaptureFixtureArming.claim(token, delays = {}).getOrThrow()
        var recorderConstructions = 0

        val selected = setupVoiceCaptureSource(
            source = source,
            setupMicrophone = {
                recorderConstructions += 1
                "microphone"
            },
            setupFixture = { "fixture" },
        )

        assertEquals("fixture", selected)
        assertEquals(0, recorderConstructions)
        source.close()
    }

    @Test
    fun `unarmed selection preserves the microphone capture path`() = runTest {
        var microphoneSetups = 0
        var fixtureSetups = 0

        val selected = setupVoiceCaptureSource(
            source = VoiceCaptureFixtureArming.claimSource(null).getOrThrow(),
            setupMicrophone = {
                microphoneSetups += 1
                "microphone"
            },
            setupFixture = {
                fixtureSetups += 1
                "fixture"
            },
        )

        assertEquals("microphone", selected)
        assertEquals(1, microphoneSetups)
        assertEquals(0, fixtureSetups)
    }

    private fun fixture(
        path: String,
        pcm16: ByteArray,
        chunkBytes: Int,
    ) = VoiceCaptureFixture(
        path = path,
        pcm16 = pcm16,
        chunkBytes = chunkBytes,
        chunkDelayMs = 1,
    )
}
