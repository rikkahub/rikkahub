package me.rerere.rikkahub.voiceagent.audio

internal class AudioTrackRetirement(
    private val pause: () -> Boolean,
    private val flush: () -> Boolean,
    private val stop: () -> Boolean,
    private val release: () -> Boolean,
    private val removeTrack: () -> Unit,
) {
    fun pauseAndFlush() {
        val paused = pause()
        val flushed = flush()
        check(paused && flushed) { "AudioTrack pause/flush failed" }
    }

    fun stopAndRelease() {
        stop()
        check(release()) { "AudioTrack release failed" }
        removeTrack()
    }
}
