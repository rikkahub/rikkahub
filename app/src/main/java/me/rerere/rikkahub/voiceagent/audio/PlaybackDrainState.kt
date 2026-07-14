package me.rerere.rikkahub.voiceagent.audio

internal class PlaybackDrainState(
    private val readPlaybackHead: () -> Int,
    private val isInterrupted: () -> Boolean,
    private val isPlaybackTrackCurrent: () -> Boolean,
    private val poll: () -> Unit,
    private val monotonicTimeNanos: () -> Long = System::nanoTime,
    private val noProgressTimeoutNanos: Long = PLAYBACK_HEAD_NO_PROGRESS_TIMEOUT_NANOS,
) {
    private var headProgress: PlaybackHeadProgress? = null
    private var writtenFrames = 0L

    fun onStarted() {
        headProgress = PlaybackHeadProgress(readPlaybackHead())
        writtenFrames = 0L
    }

    fun onBytesWritten(bytes: Int) {
        writtenFrames += bytes / PCM16_MONO_FRAME_BYTES
    }

    fun awaitDrained(): VoicePcm16Sink.DrainResult {
        val progress = headProgress
            ?: return VoicePcm16Sink.DrainResult.Failed("Playback head was not initialized")
        var lastFramesPlayed = progress.framesPlayed(readPlaybackHead())
        var lastProgressTimeNanos = monotonicTimeNanos()
        while (!isInterrupted() && isPlaybackTrackCurrent()) {
            val framesPlayed = progress.framesPlayed(readPlaybackHead())
            if (framesPlayed >= writtenFrames) {
                return VoicePcm16Sink.DrainResult.Drained
            }
            val nowNanos = monotonicTimeNanos()
            if (framesPlayed > lastFramesPlayed) {
                lastFramesPlayed = framesPlayed
                lastProgressTimeNanos = nowNanos
            } else if (nowNanos - lastProgressTimeNanos >= noProgressTimeoutNanos) {
                return VoicePcm16Sink.DrainResult.Failed("Playback head stalled")
            }
            poll()
        }
        return VoicePcm16Sink.DrainResult.Interrupted
    }

    private companion object {
        const val PCM16_MONO_FRAME_BYTES = 2
        // Far above the 10 ms Android poll and normal buffer scheduling, while still bounding the FIFO worker.
        const val PLAYBACK_HEAD_NO_PROGRESS_TIMEOUT_NANOS = 10_000_000_000L
    }
}
