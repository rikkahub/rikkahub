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

class VoiceLocalCuePlayerTest {
    @Test
    fun `local cue queued and written diagnostics are cue specific`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWrites(1))
        assertTrue(waitForDiagnostic(diagnostics) { it is VoiceLocalCueDiagnostic.ChunkWritten })

        val queued = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.ChunkQueued>().single()
        val written = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.ChunkWritten>().single()
        assertEquals(3, queued.bytes)
        assertEquals(3, written.bytes)
        assertEquals(1, sink.startCalls)
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), sink.writes)

        player.release()
        scope.cancel()
    }

    @Test
    fun `sink start failure emits local cue diagnostic and worker accepts later cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val firstSink = FakeVoicePcm16Sink(startException = IllegalStateException("start exploded"))
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(1, firstSink.stopAndReleaseCalls)
        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))
        assertTrue(secondSink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), secondSink.writes)
        assertTrue(diagnostics.any { it is VoiceLocalCueDiagnostic.SinkStartFailed })

        player.release()
        scope.cancel()
    }

    @Test
    fun `sink start failed result emits local cue diagnostic and releases sink`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            startResult = VoicePcm16Sink.StartResult.Failed("play failed"),
        )
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(1, sink.startCalls)
        assertEquals(1, sink.stopAndReleaseCalls)
        val diagnostic = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.SinkStartFailed>().single()
        assertEquals("play failed", diagnostic.message)

        player.release()
        scope.cancel()
    }

    @Test
    fun `sink write failure emits local cue diagnostic`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val writeFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            writeResult = VoicePcm16Sink.WriteResult.Failed("write failed"),
        )
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkWriteFailed) {
                    writeFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(writeFailedLatch.await(2, TimeUnit.SECONDS))

        val diagnostic = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.SinkWriteFailed>().single()
        assertEquals("write failed", diagnostic.message)

        player.release()
        scope.cancel()
    }

    @Test
    fun `sink write exception emits local cue diagnostic and worker accepts later cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val writeFailedLatch = CountDownLatch(1)
        val firstSink = FakeVoicePcm16Sink(writeException = IllegalStateException("write exploded"))
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkWriteFailed) {
                    writeFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(writeFailedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(1, firstSink.stopAndReleaseCalls)
        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))
        assertTrue(secondSink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), secondSink.writes)

        player.release()
        scope.cancel()
    }

    @Test
    fun `invalidation skips queued cue`() {
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
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val staleRejectedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(expectedWrites = 0)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.StaleCueRejected) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        try {
            assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
            player.invalidate()
            releaseWorker.countDown()

            assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))
            assertEquals(emptyList<List<Byte>>(), sink.writes)
        } finally {
            player.release()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `queued cues keep only the latest while worker is blocked`() {
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
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
        )

        try {
            assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
            assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))

            releaseWorker.countDown()

            assertTrue(sink.awaitWrites(1))
            assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)
        } finally {
            player.release()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `invalidation rejects later enqueue with invalidated token`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        var sinkCreations = 0
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                sinkCreations += 1
                sink
            },
            onDiagnostic = diagnostics::add,
        )

        player.invalidate(token = 10L)

        assertFalse(player.playBase64(base64Pcm16 = "AQID", token = 10L))
        assertEquals(0, sinkCreations)
        assertTrue(diagnostics.any { it is VoiceLocalCueDiagnostic.StaleCueRejected })

        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = 11L))
        assertTrue(sink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)

        player.release()
        scope.cancel()
    }

    @Test
    fun `invalidation interrupts active cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val staleCueLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            blockFirstWrite = true,
            interruptBlockedWriteOnPause = true,
        )
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.StaleCueRejected) {
                    staleCueLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWriteStarted())

        player.invalidate()
        sink.releaseBlockedWrite()

        assertTrue(staleCueLatch.await(2, TimeUnit.SECONDS))
        assertTrue(sink.awaitWrites(1))
        assertEquals(emptyList<List<Byte>>(), sink.writes)
        assertEquals(1, sink.pauseAndFlushCalls)
        assertEquals(1, sink.stopAndReleaseCalls)

        player.release()
        scope.cancel()
    }

    @Test
    fun `release stops invalidated sink before returning`() {
        val scope = testScope()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWrites(1))

        player.invalidate()
        player.release()

        assertEquals(1, sink.pauseAndFlushCalls)
        assertEquals(1, sink.stopAndReleaseCalls)
        scope.cancel()
    }

    @Test
    fun `repeated invalidation releases previous retired sink before retiring active sink`() {
        val scope = testScope()
        val firstSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val secondSink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) firstSink else secondSink
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(firstSink.awaitWrites(1))

        player.invalidate()

        assertEquals(1, firstSink.pauseAndFlushCalls)
        assertEquals(0, firstSink.stopAndReleaseCalls)
        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))
        assertTrue(secondSink.awaitWrites(1))

        player.invalidate()

        assertEquals(1, firstSink.stopAndReleaseCalls)
        assertEquals(1, secondSink.pauseAndFlushCalls)
        assertEquals(0, secondSink.stopAndReleaseCalls)

        player.release()

        assertEquals(1, firstSink.stopAndReleaseCalls)
        assertEquals(1, secondSink.stopAndReleaseCalls)
        scope.cancel()
    }

    @Test
    fun `malformed base64 is rejected without creating sink`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        var sinkCreations = 0
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        assertFalse(player.playBase64(base64Pcm16 = "not-base64%", token = null))

        assertEquals(0, sinkCreations)
        val diagnostic = diagnostics.filterIsInstance<VoiceLocalCueDiagnostic.MalformedCue>().single()
        assertTrue(diagnostic.message.isNotBlank())

        player.release()
        scope.cancel()
    }

    @Test
    fun `null sink factory reports start failure and worker accepts later cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) null else sink
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))
        assertTrue(sink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)
        assertTrue(diagnostics.any { it is VoiceLocalCueDiagnostic.SinkStartFailed })

        player.release()
        scope.cancel()
    }

    @Test
    fun `throwing sink factory reports start failure and worker accepts later cue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val startFailedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val sinkIndex = AtomicInteger()
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = {
                if (sinkIndex.getAndIncrement() == 0) {
                    throw IllegalStateException("factory exploded")
                } else {
                    sink
                }
            },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.SinkStartFailed) {
                    startFailedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(startFailedLatch.await(2, TimeUnit.SECONDS))

        assertTrue(player.playBase64(base64Pcm16 = "BAUG", token = null))
        assertTrue(sink.awaitWrites(1))
        assertEquals(listOf(listOf<Byte>(4, 5, 6)), sink.writes)
        assertTrue(diagnostics.any { it is VoiceLocalCueDiagnostic.SinkStartFailed })

        player.release()
        scope.cancel()
    }

    @Test
    fun `interrupted write is treated as stale cue not sink failure`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoiceLocalCueDiagnostic>()
        val staleRejectedLatch = CountDownLatch(1)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            writeResult = VoicePcm16Sink.WriteResult.Interrupted,
        )
        val player = VoiceLocalCuePlayer(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoiceLocalCueDiagnostic.StaleCueRejected) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        assertTrue(player.playBase64(base64Pcm16 = "AQID", token = null))
        assertTrue(sink.awaitWrites(1))

        assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))
        assertFalse(diagnostics.any { it is VoiceLocalCueDiagnostic.SinkWriteFailed })
        assertEquals(1, sink.stopAndReleaseCalls)

        player.release()
        scope.cancel()
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun waitForDiagnostic(
        diagnostics: List<VoiceLocalCueDiagnostic>,
        predicate: (VoiceLocalCueDiagnostic) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        while (System.nanoTime() < deadline) {
            if (diagnostics.any(predicate)) {
                return true
            }
            Thread.sleep(1)
        }
        return diagnostics.any(predicate)
    }

    private class FakeVoicePcm16Sink(
        expectedWrites: Int = 0,
        private val blockFirstWrite: Boolean = false,
        private val startException: RuntimeException? = null,
        private val startResult: VoicePcm16Sink.StartResult = VoicePcm16Sink.StartResult.Started,
        private val writeResult: VoicePcm16Sink.WriteResult? = null,
        private val writeException: RuntimeException? = null,
        private val interruptBlockedWriteOnPause: Boolean = false,
    ) : VoicePcm16Sink {
        private val writesLatch = CountDownLatch(expectedWrites)
        private val writeStartedLatch = CountDownLatch(1)
        private val releaseBlockedWriteLatch = CountDownLatch(1)
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
            return startResult
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            writeStartedLatch.countDown()
            writeException?.let { throw it }
            if (blockFirstWrite && writes.isEmpty()) {
                assertTrue(releaseBlockedWriteLatch.await(2, TimeUnit.SECONDS))
            }
            if (interruptBlockedWriteOnPause && pauseAndFlushCallCount.get() > 0) {
                writesLatch.countDown()
                return VoicePcm16Sink.WriteResult.Interrupted
            }
            val result = writeResult ?: VoicePcm16Sink.WriteResult.Written(pcm16.size)
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
