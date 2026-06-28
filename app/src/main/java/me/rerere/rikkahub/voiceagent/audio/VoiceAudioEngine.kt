package me.rerere.rikkahub.voiceagent.audio

interface VoiceAudioEngine {
    fun setErrorHandler(onError: ((String) -> Unit)?) = Unit
    fun setLocalCueErrorHandler(onError: ((String) -> Unit)?) = Unit
    fun startCapture(onPcm16: (ByteArray) -> Unit, onDebugInjectionComplete: () -> Unit = {})
    fun stopCapture()
    fun playPcm16(base64Pcm16: String)
    fun playPcm16(base64Pcm16: String, sessionId: Long?) {
        playPcm16(base64Pcm16)
    }
    fun playLocalCuePcm16(base64Pcm16: String, sessionId: Long? = null): Boolean
    fun invalidateLocalCuePlayback(sessionId: Long? = null) = Unit
    fun activatePlaybackSession(sessionId: Long) = Unit
    fun invalidatePlaybackSession() = Unit
    fun suppressPlayback()
    fun release()
}
