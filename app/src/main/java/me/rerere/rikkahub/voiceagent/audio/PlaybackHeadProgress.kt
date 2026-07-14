package me.rerere.rikkahub.voiceagent.audio

internal class PlaybackHeadProgress(startRawPosition: Int) {
    private var previous = startRawPosition.toUInt()
    private var playedFrames = 0L

    fun framesPlayed(rawPosition: Int): Long {
        val current = rawPosition.toUInt()
        playedFrames += (current - previous).toLong()
        previous = current
        return playedFrames
    }
}
