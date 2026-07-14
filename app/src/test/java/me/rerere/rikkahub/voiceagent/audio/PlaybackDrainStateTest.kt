package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDrainStateTest {
    @Test
    fun `waits for multiple writes to reach target from nonzero starting head`() {
        var rawPlaybackHead = 100
        var pollCalls = 0
        val state = PlaybackDrainState(
            readPlaybackHead = { rawPlaybackHead },
            isInterrupted = { false },
            isPlaybackTrackCurrent = { true },
            poll = {
                pollCalls += 1
                check(pollCalls == 1) { "Drain polled past the exact frame target" }
                rawPlaybackHead = 105
            },
        )

        state.onStarted()
        state.onBytesWritten(4)
        state.onBytesWritten(6)
        rawPlaybackHead = 104

        assertEquals(VoicePcm16Sink.DrainResult.Drained, state.awaitDrained())
        assertEquals(1, pollCalls)
    }

    @Test
    fun `returns interrupted when playback is interrupted while pending`() {
        var interrupted = false
        var pollCalls = 0
        val state = PlaybackDrainState(
            readPlaybackHead = { 100 },
            isInterrupted = { interrupted },
            isPlaybackTrackCurrent = { true },
            poll = {
                pollCalls += 1
                interrupted = true
            },
        )

        state.onStarted()
        state.onBytesWritten(2)

        assertEquals(VoicePcm16Sink.DrainResult.Interrupted, state.awaitDrained())
        assertEquals(1, pollCalls)
    }

    @Test
    fun `returns interrupted when playback track ownership is lost while pending`() {
        var isPlaybackTrackCurrent = true
        var pollCalls = 0
        val state = PlaybackDrainState(
            readPlaybackHead = { 100 },
            isInterrupted = { false },
            isPlaybackTrackCurrent = { isPlaybackTrackCurrent },
            poll = {
                pollCalls += 1
                isPlaybackTrackCurrent = false
            },
        )

        state.onStarted()
        state.onBytesWritten(2)

        assertEquals(VoicePcm16Sink.DrainResult.Interrupted, state.awaitDrained())
        assertEquals(1, pollCalls)
    }

    @Test
    fun `fails drain before playback has started`() {
        val state = PlaybackDrainState(
            readPlaybackHead = { 100 },
            isInterrupted = { false },
            isPlaybackTrackCurrent = { true },
            poll = {},
        )

        assertEquals(
            VoicePcm16Sink.DrainResult.Failed("Playback head was not initialized"),
            state.awaitDrained(),
        )
    }

    @Test
    fun `fails when current playback head makes no progress for bounded interval`() {
        var nowNanos = 0L
        var pollCalls = 0
        val state = PlaybackDrainState(
            readPlaybackHead = { 100 },
            isInterrupted = { false },
            isPlaybackTrackCurrent = { true },
            poll = {
                pollCalls += 1
                nowNanos = 10_000L
            },
            monotonicTimeNanos = { nowNanos },
            noProgressTimeoutNanos = 10_000L,
        )

        state.onStarted()
        state.onBytesWritten(2)

        assertEquals(
            VoicePcm16Sink.DrainResult.Failed("Playback head stalled"),
            state.awaitDrained(),
        )
        assertEquals(1, pollCalls)
    }
}
