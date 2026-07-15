package me.rerere.rikkahub.voiceagent.audio

internal class PlaybackEventDispatcher(
    private val onEvent: (VoicePlaybackEvent) -> Unit,
    private val onFailure: (VoicePlaybackEvent, Throwable) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<PendingDelivery>()
    private var draining = false

    fun enqueue(event: VoicePlaybackEvent) {
        synchronized(lock) { pending.addLast(PendingDelivery.Event(event)) }
    }

    fun drainThrough(onComplete: () -> Unit) {
        synchronized(lock) { pending.addLast(PendingDelivery.Completion(onComplete)) }
        drain()
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
            val delivery = synchronized(lock) {
                pending.removeFirstOrNull().also { next ->
                    if (next == null) draining = false
                }
            } ?: return
            when (delivery) {
                is PendingDelivery.Event -> {
                    try {
                        onEvent(delivery.event)
                    } catch (failure: Throwable) {
                        runCatching { onFailure(delivery.event, failure) }
                    }
                }
                is PendingDelivery.Completion -> runCatching(delivery.onComplete)
            }
        }
    }

    private sealed interface PendingDelivery {
        data class Event(val event: VoicePlaybackEvent) : PendingDelivery
        class Completion(val onComplete: () -> Unit) : PendingDelivery
    }
}
