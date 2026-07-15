package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackEventDispatcherTest {
    @Test
    fun `drain delivers committed events in fifo order`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        val dispatcher = PlaybackEventDispatcher(
            onEvent = delivered::add,
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(1L))
        dispatcher.enqueue(VoicePlaybackEvent.DrainStarted(1L))
        dispatcher.enqueue(VoicePlaybackEvent.Drained(1L))

        dispatcher.drain()

        assertEquals(
            listOf(
                VoicePlaybackEvent.Active(1L),
                VoicePlaybackEvent.DrainStarted(1L),
                VoicePlaybackEvent.Drained(1L),
            ),
            delivered,
        )
    }

    @Test
    fun `reentrant drain appends behind the current event`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        lateinit var dispatcher: PlaybackEventDispatcher
        dispatcher = PlaybackEventDispatcher(
            onEvent = { event ->
                delivered += event
                if (event == VoicePlaybackEvent.Active(1L)) {
                    dispatcher.enqueue(VoicePlaybackEvent.Drained(1L))
                    dispatcher.drain()
                }
            },
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(1L))

        dispatcher.drain()

        assertEquals(
            listOf(VoicePlaybackEvent.Active(1L), VoicePlaybackEvent.Drained(1L)),
            delivered,
        )
    }

    @Test
    fun `throwing handler is diagnosed and later events continue`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        val failures = mutableListOf<Pair<VoicePlaybackEvent, String>>()
        val dispatcher = PlaybackEventDispatcher(
            onEvent = { event ->
                if (event is VoicePlaybackEvent.Active) error("active failed")
                delivered += event
            },
            onFailure = { event, failure -> failures += event to failure.message.orEmpty() },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(1L))
        dispatcher.enqueue(VoicePlaybackEvent.Drained(1L))

        dispatcher.drain()

        assertEquals(
            listOf(VoicePlaybackEvent.Active(1L) to "active failed"),
            failures,
        )
        assertEquals(listOf(VoicePlaybackEvent.Drained(1L)), delivered)
    }
}
