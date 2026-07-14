package me.rerere.rikkahub.voiceagent.audio

internal interface VoicePcm16Sink {
    fun start(): StartResult
    fun writeFully(pcm16: ByteArray): WriteResult
    fun awaitDrained(): DrainResult
    fun pauseAndFlush()
    fun stopAndRelease()

    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val message: String) : StartResult
    }

    sealed interface WriteResult {
        data class Written(val bytes: Int) : WriteResult
        data class Failed(val message: String) : WriteResult
        data object Interrupted : WriteResult
    }

    sealed interface DrainResult {
        data object Drained : DrainResult
        data object Interrupted : DrainResult
        data class Failed(val message: String) : DrainResult
    }
}
