package me.rerere.rikkahub.voiceagent.audio

sealed interface VoicePlaybackEvent {
    val playbackEpoch: PlaybackEpoch

    data class Active(override val playbackEpoch: PlaybackEpoch) : VoicePlaybackEvent
    data class DrainStarted(override val playbackEpoch: PlaybackEpoch) : VoicePlaybackEvent
    data class Drained(override val playbackEpoch: PlaybackEpoch) : VoicePlaybackEvent
}
