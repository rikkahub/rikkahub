package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoicePlaybackWriterTest {
    @Test
    fun `play enqueues decoded chunks and writes them fully in order`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 2)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { _ -> sink },
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
            createSink = { _ ->
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)

        assertFalse(writer.playBase64(base64Pcm16 = "AQID", sessionId = 99L))

        assertEquals(0, sinkCreations)
        val diagnostic = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.StaleChunkRejected>().single()
        assertEquals(1L, diagnostic.activeGeneration)
        assertEquals(99L, diagnostic.rejectedSessionId)
        assertEquals(100L, diagnostic.activeSessionId)

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
            createSink = { _ -> sink },
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
    fun `invalidate session flushes sink and rejects previous session playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { _ -> sink },
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
            createSink = { _ -> sink },
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
            createSink = { _ ->
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
    fun `local cue sink start failure diagnostic keeps local cue source`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { _ ->
                FakeVoicePcm16Sink(
                    startException = IllegalStateException("start exploded"),
                )
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        assertTrue(
            writer.playBase64(
                base64Pcm16 = "AQID",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        val diagnostic = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.SinkStartFailed>().single()
        assertEquals(VoicePlaybackSource.LocalCue, diagnostic.source)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `local cue queued and written diagnostics keep local cue source`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sinkSources = CopyOnWriteArrayList<VoicePlaybackSource>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { source ->
                sinkSources += source
                sink
            },
            onDiagnostic = diagnostics::add,
        )

        assertTrue(
            writer.playBase64(
                base64Pcm16 = "AQID",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )
        assertTrue(sink.awaitWrites(1))

        val queued = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.ChunkQueued>().single()
        val written = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.ChunkWritten>().single()
        assertEquals(listOf(VoicePlaybackSource.LocalCue), sinkSources)
        assertEquals(listOf(VoicePlaybackSource.LocalCue), sink.startSources)
        assertEquals(listOf(VoicePlaybackSource.LocalCue), sink.writeSources)
        assertEquals(VoicePlaybackSource.LocalCue, queued.source)
        assertEquals(VoicePlaybackSource.LocalCue, written.source)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `local cue sink write failure diagnostic keeps local cue source`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val writeFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            writeResult = VoicePcm16Sink.WriteResult.Failed("write failed"),
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { _ -> sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.SinkWriteFailed) {
                    writeFailedLatch.countDown()
                }
            },
        )

        assertTrue(
            writer.playBase64(
                base64Pcm16 = "AQID",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )
        assertTrue(writeFailedLatch.await(2, TimeUnit.SECONDS))

        val diagnostic = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.SinkWriteFailed>().single()
        assertEquals(VoicePlaybackSource.LocalCue, diagnostic.source)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `local cue uses separate sink from assistant playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val writeFailedLatch = CountDownLatch(1)
        val assistantSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val localCueSink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            writeResultForSource = { source ->
                if (source == VoicePlaybackSource.LocalCue) {
                    VoicePcm16Sink.WriteResult.Failed("local cue write failed")
                } else {
                    null
                }
            },
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { source ->
                when (source) {
                    VoicePlaybackSource.Assistant -> assistantSink
                    VoicePlaybackSource.LocalCue -> localCueSink
                }
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
        assertTrue(assistantSink.awaitWrites(1))

        assertTrue(
            writer.playBase64(
                base64Pcm16 = "BAUG",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )
        assertTrue(writeFailedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(VoicePlaybackSource.Assistant), assistantSink.startSources)
        assertEquals(listOf(VoicePlaybackSource.Assistant), assistantSink.writeSources)
        assertEquals(listOf(VoicePlaybackSource.LocalCue), localCueSink.startSources)
        assertEquals(listOf(VoicePlaybackSource.LocalCue), localCueSink.writeSources)
        val diagnostic = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.SinkWriteFailed>().single()
        assertEquals(VoicePlaybackSource.LocalCue, diagnostic.source)
        assertEquals(null, diagnostic.audioErrorMessageOrNull())

        writer.release()
        scope.cancel()
    }

    @Test
    fun `local cue invalidation skips queued local cue without suppressing queued assistant playback`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        executor.execute {
            workerBlocked.countDown()
            releaseWorker.await(2, TimeUnit.SECONDS)
        }
        assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val staleRejectedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { _ -> sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (
                    diagnostic is VoicePlaybackDiagnostic.StaleChunkRejected &&
                    diagnostic.source == VoicePlaybackSource.LocalCue
                ) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        try {
            writer.activateSession(100L)
            assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
            assertTrue(
                writer.playBase64(
                    base64Pcm16 = "BAUG",
                    sessionId = null,
                    source = VoicePlaybackSource.LocalCue,
                ),
            )

            writer.invalidateLocalCues()
            releaseWorker.countDown()

            assertTrue(sink.awaitWrites(2))
            assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(listOf<Byte>(1, 2, 3)), sink.writes)
            assertEquals(listOf(VoicePlaybackSource.Assistant), sink.writeSources)
        } finally {
            writer.release()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `local cue invalidation interrupts active local cue without suppressing assistant playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val staleLocalCueLatch = CountDownLatch(1)
        val assistantSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val localCueSink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            blockFirstWriteForSource = VoicePlaybackSource.LocalCue,
            interruptBlockedWriteOnPause = true,
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { source ->
                when (source) {
                    VoicePlaybackSource.Assistant -> assistantSink
                    VoicePlaybackSource.LocalCue -> localCueSink
                }
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (
                    diagnostic is VoicePlaybackDiagnostic.StaleChunkRejected &&
                    diagnostic.source == VoicePlaybackSource.LocalCue
                ) {
                    staleLocalCueLatch.countDown()
                }
            },
        )

        assertTrue(
            writer.playBase64(
                base64Pcm16 = "BAUG",
                sessionId = null,
                source = VoicePlaybackSource.Assistant,
            ),
        )
        assertTrue(assistantSink.awaitWrites(2))

        assertTrue(
            writer.playBase64(
                base64Pcm16 = "AQID",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )
        assertTrue(localCueSink.awaitWriteStarted())

        writer.invalidateLocalCues()
        localCueSink.releaseBlockedWrite()

        assertTrue(staleLocalCueLatch.await(2, TimeUnit.SECONDS))
        assertTrue(localCueSink.awaitWrites(2))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), assistantSink.writes)
        assertEquals(emptyList<List<Byte>>(), localCueSink.writes)
        assertEquals(0, assistantSink.pauseAndFlushCalls)
        assertEquals(1, localCueSink.pauseAndFlushCalls)
        assertEquals(0, assistantSink.stopAndReleaseCalls)
        assertEquals(0, localCueSink.stopAndReleaseCalls)
        assertFalse(diagnostics.any { it is VoicePlaybackDiagnostic.PlaybackSuppressed })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `local cue invalidation flushes buffered local cue without flushing assistant sink`() {
        val scope = testScope()
        val assistantSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val firstLocalCueSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val secondLocalCueSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val localCueSinkIndex = AtomicInteger()
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { source ->
                when (source) {
                    VoicePlaybackSource.Assistant -> assistantSink
                    VoicePlaybackSource.LocalCue -> if (localCueSinkIndex.getAndIncrement() == 0) {
                        firstLocalCueSink
                    } else {
                        secondLocalCueSink
                    }
                }
            },
        )

        assertTrue(
            writer.playBase64(
                base64Pcm16 = "AQID",
                sessionId = null,
                source = VoicePlaybackSource.Assistant,
            ),
        )
        assertTrue(assistantSink.awaitWrites(2))
        assertTrue(
            writer.playBase64(
                base64Pcm16 = "BAUG",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )
        assertTrue(firstLocalCueSink.awaitWrites(2))

        writer.invalidateLocalCues()

        assertEquals(0, assistantSink.pauseAndFlushCalls)
        assertEquals(1, firstLocalCueSink.pauseAndFlushCalls)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), assistantSink.writes)
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), firstLocalCueSink.writes)
        assertTrue(
            writer.playBase64(
                base64Pcm16 = "BwgJ",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )
        assertTrue(secondLocalCueSink.awaitWrites(2))
        assertEquals(listOf(listOf<Byte>(7, 8, 9)), secondLocalCueSink.writes)
        assertEquals(0, secondLocalCueSink.pauseAndFlushCalls)

        writer.release()
        scope.cancel()
    }

    @Test
    fun `local cue sink failures do not map to fatal audio errors`() {
        assertEquals(
            null,
            VoicePlaybackDiagnostic.SinkStartFailed(
                message = "start failed",
                source = VoicePlaybackSource.LocalCue,
            ).audioErrorMessageOrNull(),
        )
        assertEquals(
            null,
            VoicePlaybackDiagnostic.SinkWriteFailed(
                message = "write failed",
                source = VoicePlaybackSource.LocalCue,
            ).audioErrorMessageOrNull(),
        )
    }

    @Test
    fun `assistant sink failures still map to fatal audio errors`() {
        assertEquals(
            "AudioTrack start failed: start failed",
            VoicePlaybackDiagnostic.SinkStartFailed(
                message = "start failed",
                source = VoicePlaybackSource.Assistant,
            ).audioErrorMessageOrNull(),
        )
        assertEquals(
            "AudioTrack write failed: write failed",
            VoicePlaybackDiagnostic.SinkWriteFailed(
                message = "write failed",
                source = VoicePlaybackSource.Assistant,
            ).audioErrorMessageOrNull(),
        )
    }

    @Test
    fun `release stops sink and rejects future playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { _ -> sink },
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
            createSink = { _ ->
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
    fun `malformed local cue base64 is rejected without creating sink or fatal audio error`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        var sinkCreations = 0
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { _ ->
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        assertFalse(
            writer.playBase64(
                base64Pcm16 = "not-base64%",
                sessionId = null,
                source = VoicePlaybackSource.LocalCue,
            ),
        )

        assertEquals(0, sinkCreations)
        val diagnostic = diagnostics.filterIsInstance<VoicePlaybackDiagnostic.MalformedChunk>().single()
        assertEquals(VoicePlaybackSource.LocalCue, diagnostic.source)
        assertEquals(null, diagnostic.audioErrorMessageOrNull())

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
            createSink = { _ ->
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
            createSink = { _ -> sink },
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
        assertEquals(0, sink.stopAndReleaseCalls)

        writer.release()
        scope.cancel()
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class FakeVoicePcm16Sink(
        expectedWrites: Int = 0,
        private val blockFirstWrite: Boolean = false,
        private val blockFirstWriteForSource: VoicePlaybackSource? = null,
        private val startException: RuntimeException? = null,
        private val writeResult: VoicePcm16Sink.WriteResult? = null,
        private val writeResultForSource: (VoicePlaybackSource) -> VoicePcm16Sink.WriteResult? = { writeResult },
        private val interruptBlockedWriteOnPause: Boolean = false,
    ) : VoicePcm16Sink {
        private val writesLatch = CountDownLatch(expectedWrites)
        private val writeStartedLatch = CountDownLatch(1)
        private val releaseBlockedWriteLatch = CountDownLatch(1)
        private val startCallCount = AtomicInteger()
        private val pauseAndFlushCallCount = AtomicInteger()
        private val stopAndReleaseCallCount = AtomicInteger()
        val writes = mutableListOf<List<Byte>>()
        val startSources = CopyOnWriteArrayList<VoicePlaybackSource>()
        val writeSources = CopyOnWriteArrayList<VoicePlaybackSource>()

        val startCalls: Int
            get() = startCallCount.get()

        val pauseAndFlushCalls: Int
            get() = pauseAndFlushCallCount.get()

        val stopAndReleaseCalls: Int
            get() = stopAndReleaseCallCount.get()

        override fun start(source: VoicePlaybackSource): VoicePcm16Sink.StartResult {
            startSources += source
            startCallCount.incrementAndGet()
            startException?.let { throw it }
            return VoicePcm16Sink.StartResult.Started
        }

        override fun writeFully(pcm16: ByteArray, source: VoicePlaybackSource): VoicePcm16Sink.WriteResult {
            writeSources += source
            writeStartedLatch.countDown()
            val shouldBlock = (blockFirstWrite && writes.isEmpty()) || source == blockFirstWriteForSource
            if (shouldBlock) {
                assertTrue(releaseBlockedWriteLatch.await(2, TimeUnit.SECONDS))
            }
            if (
                interruptBlockedWriteOnPause &&
                source == VoicePlaybackSource.LocalCue &&
                pauseAndFlushCallCount.get() > 0
            ) {
                writesLatch.countDown()
                return VoicePcm16Sink.WriteResult.Interrupted
            }
            val result = writeResultForSource(source) ?: VoicePcm16Sink.WriteResult.Written(pcm16.size)
            if (result is VoicePcm16Sink.WriteResult.Written) {
                writes += pcm16.toList()
            }
            writesLatch.countDown()
            return result
        }

        override fun pauseAndFlush() {
            pauseAndFlushCallCount.incrementAndGet()
        }

        override fun stopAndRelease() {
            stopAndReleaseCallCount.incrementAndGet()
        }

        fun awaitWrites(seconds: Long): Boolean = writesLatch.await(seconds, TimeUnit.SECONDS)

        fun awaitWriteStarted(): Boolean = writeStartedLatch.await(2, TimeUnit.SECONDS)

        fun releaseBlockedWrite() {
            releaseBlockedWriteLatch.countDown()
        }
    }
}
