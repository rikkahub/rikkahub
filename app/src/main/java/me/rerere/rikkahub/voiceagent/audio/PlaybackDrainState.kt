package me.rerere.rikkahub.voiceagent.audio

internal class PlaybackDrainState(
    private val readPlaybackHead: () -> Int,
    private val isInterrupted: () -> Boolean,
    private val isPlaybackTrackCurrent: () -> Boolean,
    private val poll: () -> Unit,
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
        while (!isInterrupted() && isPlaybackTrackCurrent()) {
            if (progress.framesPlayed(readPlaybackHead()) >= writtenFrames) {
                return VoicePcm16Sink.DrainResult.Drained
            }
            poll()
        }
        return VoicePcm16Sink.DrainResult.Interrupted
    }

    private companion object {
        const val PCM16_MONO_FRAME_BYTES = 2
    }
}
