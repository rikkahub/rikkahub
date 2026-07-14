package me.rerere.rikkahub.voiceagent.audio

internal class VoicePlaybackEventOwner {
    private val lock = Any()
    private var handler: ((VoicePlaybackEvent) -> Unit)? = null

    fun setHandler(onEvent: ((VoicePlaybackEvent) -> Unit)?) {
        synchronized(lock) {
            handler = onEvent
        }
    }

    fun notify(event: VoicePlaybackEvent) {
        val currentHandler = synchronized(lock) { handler }
        currentHandler?.invoke(event)
    }

    fun markTurnComplete(
        sessionId: Long?,
        markPlaybackTurnComplete: (Long?) -> Boolean,
    ): Boolean = markPlaybackTurnComplete(sessionId)

    fun releasePlayback(release: () -> Unit) {
        release()
        synchronized(lock) {
            handler = null
        }
    }
}
