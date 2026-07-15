package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VoicePlaybackWriterTest {
    @Test
    fun `play enqueues decoded chunks and writes them fully in order`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 2)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)

        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertTrue(sink.awaitWrites(2))

        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5, 6)), sink.writes)
        assertEquals(1, sink.startCalls)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.ChunkQueued && it.bytes == 3 })
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.ChunkWritten && it.bytes == 3 })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `play rejects stale session before enqueue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        var sinkCreations = 0
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)

        assertFalse(writer.playBase64(base64Pcm16 = "AQID", sessionId = 99L))

        assertEquals(0, sinkCreations)
        val diagnostic = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.StaleChunkRejected>().single()
        assertEquals(WriterGeneration(1L), diagnostic.activeWriterGeneration)
        assertEquals(99L, diagnostic.rejectedSessionId)
        assertEquals(100L, diagnostic.activeSessionId)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `stale turn boundary leaves current epoch accepting until correct boundary`() {
        val scope = testScope()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 2, blockDrain = true)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))

        assertFalse(writer.markTurnComplete(sessionId = 99L))
        assertEquals(listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L))), events)

        assertTrue(writer.playBase64("BAUG", sessionId = 100L))
        assertEquals(listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L))), events)
        assertTrue(writer.markTurnComplete(sessionId = 100L))
        assertTrue(sink.awaitDrainStarted())
        assertEquals(2, sink.writes.size)
        assertEquals(
            listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L)), VoicePlaybackEvent.DrainStarted(PlaybackEpoch(1L))),
            events,
        )

        sink.releaseDrain()
        assertTrue(waitUntil { events.lastOrNull() == VoicePlaybackEvent.Drained(PlaybackEpoch(1L)) })
        writer.release()
        scope.cancel()
    }

    @Test
    fun `suppress increments generation retires active sink and skips queued stale chunks`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val staleRejectedLatch = CountDownLatch(2)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            blockFirstWrite = true,
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.StaleChunkRejected) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWriteStarted())
        assertTrue(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))

        writer.suppress()
        sink.releaseBlockedWrite()
        assertTrue(sink.awaitWrites(1))
        assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(listOf<Byte>(1, 2, 3)), sink.writes)
        assertEquals(1, sink.stopAndReleaseCalls)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.PlaybackSuppressed })
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.StaleChunkRejected })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `playback admission stays closed while suppression retirement is blocked`() {
        val scope = testScope()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val firstSink = FakeVoicePcm16Sink(expectedWrites = 1, blockPauseAndFlush = true)
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(firstSink.awaitWrites(2))

        val suppression = Thread { writer.suppress() }.apply { start() }
        assertTrue(firstSink.awaitPauseAndFlushStarted())

        assertFalse(writer.playBase64("BAUG", sessionId = 100L))
        assertEquals(1, sinkIndex.get())
        assertFalse(events.contains(VoicePlaybackEvent.Active(PlaybackEpoch(2L))))

        firstSink.releasePauseAndFlush()
        suppression.join(2_000L)
        assertFalse(suppression.isAlive)
        assertTrue(waitUntil { events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))) })

        assertTrue(writer.playBase64("BAUG", sessionId = 100L))
        assertTrue(secondSink.awaitWrites(2))
        assertTrue(events.contains(VoicePlaybackEvent.Active(PlaybackEpoch(2L))))
        writer.release()
        scope.cancel()
    }

    @Test
    fun `suppress emits diagnostic when sink cleanup throws`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            releaseException = IllegalStateException("release exploded"),
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWrites(1))

        writer.suppress()

        assertEquals(1, sink.stopAndReleaseCalls)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.PlaybackSuppressed })
        writer.release()
        scope.cancel()
    }

    @Test
    fun `invalidate session flushes sink and rejects previous session playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWrites(2))

        writer.invalidateSession()

        assertEquals(1, sink.stopAndReleaseCalls)
        assertFalse(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), sink.writes)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.StaleChunkRejected })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `release during blocked failed write stops sink only once`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val writeFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            blockFirstWrite = true,
            writeResult = VoicePcm16Sink.WriteResult.Failed("write failed"),
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.SinkWriteFailed) {
                    writeFailedLatch.countDown()
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWriteStarted())

        writer.release()
        sink.releaseBlockedWrite()

        assertTrue(writeFailedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(1, sink.stopAndReleaseCalls)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.Released })

        scope.cancel()
    }

    @Test
    fun `release emits diagnostic when sink cleanup throws`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            releaseException = IllegalStateException("release exploded"),
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWrites(1))

        writer.release()

        assertEquals(1, sink.stopAndReleaseCalls)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.Released })
        scope.cancel()
    }

    @Test
    fun `write exception emits diagnostic and worker accepts later playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val writeFailedLatch = CountDownLatch(1)
        val firstSink = FakeVoicePcm16Sink(writeException = IllegalStateException("write exploded"))
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.SinkWriteFailed) {
                    writeFailedLatch.countDown()
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(writeFailedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(1, firstSink.stopAndReleaseCalls)
        assertTrue(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertTrue(secondSink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), secondSink.writes)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `throwing sink start is released and worker accepts later playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val firstSink = FakeVoicePcm16Sink(
            startException = IllegalStateException("start exploded"),
        )
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(1, firstSink.stopAndReleaseCalls)
        assertTrue(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertTrue(secondSink.awaitWrites(2))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), secondSink.writes)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `release stops sink and rejects future playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWrites(1))

        writer.release()

        assertEquals(1, sink.stopAndReleaseCalls)
        assertFalse(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.Released })

        scope.cancel()
    }

    @Test
    fun `malformed base64 is rejected without creating sink`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        var sinkCreations = 0
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)

        assertFalse(writer.playBase64(base64Pcm16 = "not-base64%", sessionId = 100L))

        assertEquals(0, sinkCreations)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.MalformedChunk })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `null sink factory reports start failure and worker accepts later playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) null else sink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertTrue(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertTrue(sink.awaitWrites(2))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.SinkStartFailed })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `interrupted write is treated as stale playback not sink failure`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val staleRejectedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            writeResult = VoicePcm16Sink.WriteResult.Interrupted,
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.StaleChunkRejected) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWrites(2))

        assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))
        assertFalse(diagnostics.any { it is VoicePlaybackDiagnostic.SinkWriteFailed })
        assertEquals(1, sink.stopAndReleaseCalls)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `turn boundary drains after every previously queued chunk`() {
        val scope = testScope()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 2, blockDrain = true)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.playBase64("BAUG", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))
        assertTrue(sink.awaitDrainStarted())

        assertEquals(2, sink.writes.size)
        assertEquals(
            listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L)), VoicePlaybackEvent.DrainStarted(PlaybackEpoch(1L))),
            events,
        )

        sink.releaseDrain()
        assertTrue(waitUntil { events.lastOrNull() == VoicePlaybackEvent.Drained(PlaybackEpoch(1L)) })
        writer.release()
        scope.cancel()
    }

    @Test
    fun `stale drain completion cannot drain a replacement generation`() {
        val scope = testScope()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val firstSink = FakeVoicePcm16Sink(expectedWrites = 1, blockDrain = true)
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))
        assertTrue(firstSink.awaitDrainStarted())

        writer.activateSession(200L)
        assertTrue(writer.playBase64("BAUG", sessionId = 200L))
        assertTrue(events.contains(VoicePlaybackEvent.Active(PlaybackEpoch(2L))))
        assertFalse(events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(2L))))

        firstSink.releaseDrain()
        assertTrue(waitUntil { events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))) })
        assertTrue(secondSink.awaitWrites(2))
        assertFalse(events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(2L))))

        assertTrue(writer.markTurnComplete(sessionId = 200L))
        assertTrue(waitUntil { events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(2L))) })
        assertEquals(1, events.count { it == VoicePlaybackEvent.Drained(PlaybackEpoch(2L)) })
        writer.release()
        scope.cancel()
    }

    @Test
    fun `second response in one session gets a new playback epoch`() {
        val scope = testScope()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 2)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))
        assertTrue(waitUntil { events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))) })

        assertTrue(writer.playBase64("BAUG", sessionId = 100L))
        assertTrue(waitUntil { events.contains(VoicePlaybackEvent.Active(PlaybackEpoch(2L))) })
        assertTrue(writer.markTurnComplete(sessionId = 100L))
        assertTrue(waitUntil { events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(2L))) })

        assertEquals(
            listOf(
                VoicePlaybackEvent.Active(PlaybackEpoch(1L)),
                VoicePlaybackEvent.DrainStarted(PlaybackEpoch(1L)),
                VoicePlaybackEvent.Drained(PlaybackEpoch(1L)),
                VoicePlaybackEvent.Active(PlaybackEpoch(2L)),
                VoicePlaybackEvent.DrainStarted(PlaybackEpoch(2L)),
                VoicePlaybackEvent.Drained(PlaybackEpoch(2L)),
            ),
            events,
        )
        writer.release()
        scope.cancel()
    }

    @Test
    fun `drain failure flushes the sink before retiring the epoch`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            drainResult = VoicePcm16Sink.DrainResult.Failed("head read failed"),
        )
        var flushedBeforeDrained = false
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
            onPlaybackEvent = { event ->
                if (event == VoicePlaybackEvent.Drained(PlaybackEpoch(1L))) {
                    flushedBeforeDrained = sink.pauseAndFlushCalls == 1
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))

        assertTrue(waitUntil { flushedBeforeDrained })
        assertTrue(diagnostics.any {
            it is VoicePlaybackDiagnostic.SinkDrainFailed &&
                it.message == "head read failed"
        })
        writer.release()
        scope.cancel()
    }

    @Test
    fun `failed drain and failed sink retirement keep the epoch blocked`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            drainResult = VoicePcm16Sink.DrainResult.Failed("Playback head stalled"),
            pauseException = IllegalStateException("flush failed"),
            releaseException = IllegalStateException("release failed"),
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))
        assertTrue(waitUntil {
            diagnostics.any { it is VoicePlaybackDiagnostic.SinkRetirementFailed }
        })

        assertFalse(events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))))
        assertFalse(writer.playBase64("BAUG", sessionId = 100L))
        writer.release()
        scope.cancel()
    }

    @Test
    fun `stalled drain retires safely and worker accepts later playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val firstSink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            drainResult = VoicePcm16Sink.DrainResult.Failed("Playback head stalled"),
        )
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onDiagnostic = diagnostics::add,
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))

        assertTrue(waitUntil { events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))) })
        assertEquals(1, firstSink.pauseAndFlushCalls)
        assertTrue(diagnostics.any {
            it is VoicePlaybackDiagnostic.SinkDrainFailed &&
                it.message == "Playback head stalled"
        })

        assertTrue(writer.playBase64("BAUG", sessionId = 100L))
        assertTrue(secondSink.awaitWrites(2))
        writer.release()
        scope.cancel()
    }

    @Test
    fun `failed start retirement keeps epoch blocked`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val sink = FakeVoicePcm16Sink(
            startException = IllegalStateException("start failed"),
            pauseException = IllegalStateException("flush failed"),
            releaseException = IllegalStateException("release failed"),
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
            onPlaybackEvent = events::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(waitUntil {
            diagnostics.any { it is VoicePlaybackDiagnostic.SinkRetirementFailed }
        })

        assertFalse(events.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))))
        assertFalse(writer.playBase64("BAUG", sessionId = 100L))
        writer.release()
        scope.cancel()
    }

    @Test
    fun `failed retirement prevents an in-flight normal drain from committing drained`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val drainReadyToReturn = CountDownLatch(1)
        val resumeDrainReturn = CountDownLatch(1)
        val staleQueuedPlay = CountDownLatch(1)
        val drainReturnTimedOut = AtomicBoolean(false)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            pauseException = IllegalStateException("flush failed"),
            releaseException = IllegalStateException("release failed"),
            beforeDrainReturn = {
                drainReadyToReturn.countDown()
                drainReturnTimedOut.set(!resumeDrainReturn.await(2, TimeUnit.SECONDS))
            },
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.StaleChunkRejected) {
                    staleQueuedPlay.countDown()
                }
            },
            onPlaybackEvent = events::add,
        )
        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))
        assertTrue(drainReadyToReturn.await(2, TimeUnit.SECONDS))
        assertTrue(writer.playBase64("BAUG", sessionId = 100L))

        writer.suppress()
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.SinkRetirementFailed })
        resumeDrainReturn.countDown()

        assertTrue(staleQueuedPlay.await(2, TimeUnit.SECONDS))
        assertFalse(drainReturnTimedOut.get())
        assertFalse(events.any { it is VoicePlaybackEvent.Drained })
        assertFalse(writer.playBase64("BwgJ", sessionId = 100L))
        writer.release()
        scope.cancel()
    }

    @Test
    fun `active callback runs after the writer state lock is released`() {
        val scope = testScope()
        val callbackObservedUnlocked = AtomicBoolean(false)
        lateinit var writer: VoicePlaybackWriter
        writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { FakeVoicePcm16Sink(expectedWrites = 1) },
            onPlaybackEvent = { event ->
                if (event is VoicePlaybackEvent.Active) {
                    val completed = CountDownLatch(1)
                    thread(start = true, name = "playback-reentrant-boundary") {
                        writer.markTurnComplete(sessionId = 100L)
                        completed.countDown()
                    }
                    callbackObservedUnlocked.set(completed.await(500, TimeUnit.MILLISECONDS))
                }
            },
        )
        writer.activateSession(100L)

        assertTrue(writer.playBase64("AQID", sessionId = 100L))

        assertTrue(callbackObservedUnlocked.get())
        writer.release()
        scope.cancel()
    }

    @Test
    fun `throwing active callback does not kill later drain delivery`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val delivered = CopyOnWriteArrayList<VoicePlaybackEvent>()
        var throwActive = true
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { FakeVoicePcm16Sink(expectedWrites = 1) },
            onDiagnostic = diagnostics::add,
            onPlaybackEvent = { event ->
                if (event is VoicePlaybackEvent.Active && throwActive) {
                    throwActive = false
                    error("handler failed")
                }
                delivered += event
            },
        )
        writer.activateSession(100L)
        assertTrue(writer.playBase64("AQID", sessionId = 100L))
        assertTrue(writer.markTurnComplete(sessionId = 100L))

        assertTrue(waitUntil { delivered.contains(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))) })
        assertTrue(diagnostics.any {
            it is VoicePlaybackDiagnostic.PlaybackEventHandlerFailed && it.message == "handler failed"
        })
        writer.release()
        scope.cancel()
    }

    @Test
    fun `concurrent release delivers drained before the event owner clears its handler`() {
        val scope = testScope()
        val owner = VoicePlaybackEventOwner()
        val events = CopyOnWriteArrayList<VoicePlaybackEvent>()
        val activeCallbackStarted = CountDownLatch(1)
        val releaseActiveCallback = CountDownLatch(1)
        val drainedDelivered = CountDownLatch(1)
        val activeCallbackTimedOut = AtomicBoolean(false)
        owner.setHandler { event ->
            events += event
            when (event) {
                is VoicePlaybackEvent.Active -> {
                    activeCallbackStarted.countDown()
                    activeCallbackTimedOut.set(!releaseActiveCallback.await(2, TimeUnit.SECONDS))
                }
                is VoicePlaybackEvent.Drained -> drainedDelivered.countDown()
                is VoicePlaybackEvent.DrainStarted -> Unit
            }
        }
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { FakeVoicePcm16Sink(expectedWrites = 1) },
            onPlaybackEvent = owner::notify,
        )
        writer.activateSession(100L)
        val play = thread(start = true, name = "playback-active-callback") {
            writer.playBase64("AQID", sessionId = 100L)
        }
        assertTrue(activeCallbackStarted.await(2, TimeUnit.SECONDS))

        owner.releasePlayback(writer::release)
        releaseActiveCallback.countDown()

        assertTrue(drainedDelivered.await(2, TimeUnit.SECONDS))
        play.join(2_000L)
        assertFalse(play.isAlive)
        assertFalse(activeCallbackTimedOut.get())
        assertEquals(
            listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L)), VoicePlaybackEvent.Drained(PlaybackEpoch(1L))),
            events,
        )
        scope.cancel()
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.yield()
        }
        return predicate()
    }

    private class FakeVoicePcm16Sink(
        expectedWrites: Int = 0,
        private val blockFirstWrite: Boolean = false,
        private val startException: RuntimeException? = null,
        private val writeResult: VoicePcm16Sink.WriteResult? = null,
        private val writeException: RuntimeException? = null,
        private val blockDrain: Boolean = false,
        private val drainResult: VoicePcm16Sink.DrainResult = VoicePcm16Sink.DrainResult.Drained,
        private val blockPauseAndFlush: Boolean = false,
        private val pauseException: RuntimeException? = null,
        private val releaseException: RuntimeException? = null,
        private val beforeDrainReturn: (() -> Unit)? = null,
    ) : VoicePcm16Sink {
        private val writesLatch = CountDownLatch(expectedWrites)
        private val writeStartedLatch = CountDownLatch(1)
        private val releaseBlockedWriteLatch = CountDownLatch(1)
        private val drainStartedLatch = CountDownLatch(1)
        private val releaseDrainLatch = CountDownLatch(1)
        private val pauseAndFlushStartedLatch = CountDownLatch(1)
        private val releasePauseAndFlushLatch = CountDownLatch(1)
        private val startCallCount = AtomicInteger()
        private val pauseAndFlushCallCount = AtomicInteger()
        private val stopAndReleaseCallCount = AtomicInteger()
        val writes = mutableListOf<List<Byte>>()

        val startCalls: Int
            get() = startCallCount.get()

        val pauseAndFlushCalls: Int
            get() = pauseAndFlushCallCount.get()

        val stopAndReleaseCalls: Int
            get() = stopAndReleaseCallCount.get()

        override fun start(): VoicePcm16Sink.StartResult {
            startCallCount.incrementAndGet()
            startException?.let { throw it }
            return VoicePcm16Sink.StartResult.Started
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            writeStartedLatch.countDown()
            writeException?.let { throw it }
            if (blockFirstWrite && writes.isEmpty()) {
                assertTrue(releaseBlockedWriteLatch.await(2, TimeUnit.SECONDS))
            }
            val result = writeResult ?: VoicePcm16Sink.WriteResult.Written(pcm16.size)
            if (result is VoicePcm16Sink.WriteResult.Written) {
                writes += pcm16.toList()
            }
            writesLatch.countDown()
            return result
        }

        override fun awaitDrained(): VoicePcm16Sink.DrainResult {
            drainStartedLatch.countDown()
            if (blockDrain) releaseDrainLatch.await(2, TimeUnit.SECONDS)
            val result = if (pauseAndFlushCalls > 0 || stopAndReleaseCalls > 0) {
                VoicePcm16Sink.DrainResult.Interrupted
            } else {
                drainResult
            }
            beforeDrainReturn?.invoke()
            return result
        }

        override fun pauseAndFlush() {
            pauseAndFlushCallCount.incrementAndGet()
            pauseAndFlushStartedLatch.countDown()
            if (blockPauseAndFlush) releasePauseAndFlushLatch.await(2, TimeUnit.SECONDS)
            pauseException?.let { throw it }
        }

        override fun stopAndRelease() {
            stopAndReleaseCallCount.incrementAndGet()
            releaseException?.let { throw it }
        }

        fun awaitWrites(seconds: Long): Boolean = writesLatch.await(seconds, TimeUnit.SECONDS)

        fun awaitWriteStarted(): Boolean = writeStartedLatch.await(2, TimeUnit.SECONDS)

        fun awaitDrainStarted(): Boolean = drainStartedLatch.await(2, TimeUnit.SECONDS)

        fun releaseDrain() = releaseDrainLatch.countDown()

        fun awaitPauseAndFlushStarted(): Boolean =
            pauseAndFlushStartedLatch.await(2, TimeUnit.SECONDS)

        fun releasePauseAndFlush() = releasePauseAndFlushLatch.countDown()

        fun releaseBlockedWrite() {
            releaseBlockedWriteLatch.countDown()
        }
    }
}
