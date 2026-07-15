package me.rerere.rikkahub.voiceagent.audio

internal class PlaybackEventDispatcher(
    private val onEvent: (VoicePlaybackEvent) -> Unit,
    private val onFailure: (VoicePlaybackEvent, Throwable) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<VoicePlaybackEvent>()
    private var draining = false

    fun enqueue(event: VoicePlaybackEvent) {
        synchronized(lock) { pending.addLast(event) }
    }

    fun drain() {
        val ownsDrain = synchronized(lock) {
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (!ownsDrain) return

        while (true) {
            val event = synchronized(lock) {
                pending.removeFirstOrNull().also { next ->
                    if (next == null) draining = false
                }
            } ?: return
            try {
                onEvent(event)
            } catch (failure: Throwable) {
                runCatching { onFailure(event, failure) }
            }
        }
    }
}
