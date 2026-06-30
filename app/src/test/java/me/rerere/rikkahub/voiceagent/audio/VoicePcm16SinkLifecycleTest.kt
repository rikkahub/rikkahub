package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VoicePcm16SinkLifecycleTest {
    @Test
    fun `createStarted returns started sink and calls start once`() {
        val sink = FakeVoicePcm16Sink()

        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { sink },
            nullSinkMessage = "sink missing",
        )

        assertEquals(VoicePcm16SinkLifecycle.StartOutcome.Started(sink), outcome)
        assertSame(sink, (outcome as VoicePcm16SinkLifecycle.StartOutcome.Started).sink)
        assertEquals(1, sink.startCalls)
        assertEquals(0, sink.stopAndReleaseCalls)
    }

    @Test
    fun `createStarted maps null sink to failed null sink message`() {
        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { null },
            nullSinkMessage = "sink missing",
        )

        assertEquals(VoicePcm16SinkLifecycle.StartOutcome.Failed("sink missing"), outcome)
    }

    @Test
    fun `createStarted maps thrown create sink exception to failed message`() {
        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { throw IllegalStateException("create exploded") },
            nullSinkMessage = "sink missing",
        )

        assertEquals(VoicePcm16SinkLifecycle.StartOutcome.Failed("create exploded"), outcome)
    }

    @Test
    fun `createStarted releases sink and returns failed message when start result fails`() {
        val sink = FakeVoicePcm16Sink(
            startResult = VoicePcm16Sink.StartResult.Failed("play failed"),
        )

        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { sink },
            nullSinkMessage = "sink missing",
        )

        assertEquals(VoicePcm16SinkLifecycle.StartOutcome.Failed("play failed"), outcome)
        assertEquals(1, sink.startCalls)
        assertEquals(1, sink.stopAndReleaseCalls)
    }

    @Test
    fun `createStarted releases sink and returns failed message when start throws`() {
        val sink = FakeVoicePcm16Sink(
            startException = IllegalStateException("start exploded"),
        )

        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { sink },
            nullSinkMessage = "sink missing",
        )

        assertEquals(VoicePcm16SinkLifecycle.StartOutcome.Failed("start exploded"), outcome)
        assertEquals(1, sink.startCalls)
        assertEquals(1, sink.stopAndReleaseCalls)
    }

    @Test
    fun `createStarted maps thrown start exception without message to failed class name`() {
        val sink = FakeVoicePcm16Sink(
            startException = IllegalStateException(),
        )

        val outcome = VoicePcm16SinkLifecycle.createStarted(
            createSink = { sink },
            nullSinkMessage = "sink missing",
        )

        assertEquals(VoicePcm16SinkLifecycle.StartOutcome.Failed("IllegalStateException"), outcome)
        assertEquals(1, sink.startCalls)
        assertEquals(1, sink.stopAndReleaseCalls)
    }

    @Test
    fun `writeFully maps written result`() {
        val sink = FakeVoicePcm16Sink()

        val outcome = VoicePcm16SinkLifecycle.writeFully(sink, byteArrayOf(1, 2, 3))

        assertEquals(VoicePcm16Sink.WriteResult.Written(3), outcome)
    }

    @Test
    fun `writeFully maps failed result`() {
        val sink = FakeVoicePcm16Sink(
            writeResult = VoicePcm16Sink.WriteResult.Failed("write failed"),
        )

        val outcome = VoicePcm16SinkLifecycle.writeFully(sink, byteArrayOf(1, 2, 3))

        assertEquals(VoicePcm16Sink.WriteResult.Failed("write failed"), outcome)
    }

    @Test
    fun `writeFully maps interrupted result`() {
        val sink = FakeVoicePcm16Sink(
            writeResult = VoicePcm16Sink.WriteResult.Interrupted,
        )

        val outcome = VoicePcm16SinkLifecycle.writeFully(sink, byteArrayOf(1, 2, 3))

        assertEquals(VoicePcm16Sink.WriteResult.Interrupted, outcome)
    }

    @Test
    fun `writeFully maps thrown exception to failed message`() {
        val sink = FakeVoicePcm16Sink(
            writeException = IllegalStateException("write exploded"),
        )

        val outcome = VoicePcm16SinkLifecycle.writeFully(sink, byteArrayOf(1, 2, 3))

        assertEquals(VoicePcm16Sink.WriteResult.Failed("write exploded"), outcome)
    }

    @Test
    fun `writeFully maps thrown exception without message to failed class name`() {
        val sink = FakeVoicePcm16Sink(
            writeException = IllegalStateException(),
        )

        val outcome = VoicePcm16SinkLifecycle.writeFully(sink, byteArrayOf(1, 2, 3))

        assertEquals(VoicePcm16Sink.WriteResult.Failed("IllegalStateException"), outcome)
    }

    @Test
    fun `release helpers ignore sink exceptions and do not double release same sink`() {
        val sink = FakeVoicePcm16Sink(
            pauseException = IllegalStateException("pause exploded"),
            releaseException = IllegalStateException("release exploded"),
        )

        VoicePcm16SinkLifecycle.pauseAndFlushSafely(sink)
        VoicePcm16SinkLifecycle.stopAndReleaseSafely(sink)
        VoicePcm16SinkLifecycle.pauseAndFlushSafely(null)
        VoicePcm16SinkLifecycle.stopAndReleaseSafely(null)

        assertEquals(1, sink.pauseAndFlushCalls)
        assertEquals(1, sink.stopAndReleaseCalls)
    }

    private class FakeVoicePcm16Sink(
        private val startResult: VoicePcm16Sink.StartResult = VoicePcm16Sink.StartResult.Started,
        private val writeResult: VoicePcm16Sink.WriteResult? = null,
        private val startException: RuntimeException? = null,
        private val writeException: RuntimeException? = null,
        private val pauseException: RuntimeException? = null,
        private val releaseException: RuntimeException? = null,
    ) : VoicePcm16Sink {
        var startCalls = 0
            private set
        var pauseAndFlushCalls = 0
            private set
        var stopAndReleaseCalls = 0
            private set

        override fun start(): VoicePcm16Sink.StartResult {
            startCalls += 1
            startException?.let { throw it }
            return startResult
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            writeException?.let { throw it }
            return writeResult ?: VoicePcm16Sink.WriteResult.Written(pcm16.size)
        }

        override fun pauseAndFlush() {
            pauseAndFlushCalls += 1
            pauseException?.let { throw it }
        }

        override fun stopAndRelease() {
            stopAndReleaseCalls += 1
            releaseException?.let { throw it }
        }
    }
}
