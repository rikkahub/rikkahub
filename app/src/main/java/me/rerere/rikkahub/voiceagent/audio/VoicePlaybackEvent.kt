package me.rerere.rikkahub.voiceagent.audio

sealed interface VoicePlaybackEvent {
    val generation: Long

    data class Active(override val generation: Long) : VoicePlaybackEvent
    data class DrainStarted(override val generation: Long) : VoicePlaybackEvent
    data class Drained(override val generation: Long) : VoicePlaybackEvent
}
