package me.rerere.rikkahub.voiceagent.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEventDispatcherTest {
    @Test
    fun `drain delivers committed events in fifo order`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        val dispatcher = PlaybackEventDispatcher(
            onEvent = delivered::add,
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(PlaybackEpoch(1L)))
        dispatcher.enqueue(VoicePlaybackEvent.DrainStarted(PlaybackEpoch(1L)))
        dispatcher.enqueue(VoicePlaybackEvent.Drained(PlaybackEpoch(1L)))

        dispatcher.drain()

        assertEquals(
            listOf(
                VoicePlaybackEvent.Active(PlaybackEpoch(1L)),
                VoicePlaybackEvent.DrainStarted(PlaybackEpoch(1L)),
                VoicePlaybackEvent.Drained(PlaybackEpoch(1L)),
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
                if (event == VoicePlaybackEvent.Active(PlaybackEpoch(1L))) {
                    dispatcher.enqueue(VoicePlaybackEvent.Drained(PlaybackEpoch(1L)))
                    dispatcher.drain()
                }
            },
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(PlaybackEpoch(1L)))

        dispatcher.drain()

        assertEquals(
            listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L)), VoicePlaybackEvent.Drained(PlaybackEpoch(1L))),
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
        dispatcher.enqueue(VoicePlaybackEvent.Active(PlaybackEpoch(1L)))
        dispatcher.enqueue(VoicePlaybackEvent.Drained(PlaybackEpoch(1L)))

        dispatcher.drain()

        assertEquals(
            listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L)) to "active failed"),
            failures,
        )
        assertEquals(listOf(VoicePlaybackEvent.Drained(PlaybackEpoch(1L))), delivered)
    }

    @Test
    fun `reentrant completion boundary runs after the handler and queued events`() {
        val delivered = mutableListOf<String>()
        lateinit var dispatcher: PlaybackEventDispatcher
        dispatcher = PlaybackEventDispatcher(
            onEvent = { event ->
                delivered += "start:$event"
                if (event == VoicePlaybackEvent.Active(PlaybackEpoch(1L))) {
                    dispatcher.enqueue(VoicePlaybackEvent.Drained(PlaybackEpoch(1L)))
                    dispatcher.drainThrough { delivered += "complete" }
                }
                delivered += "end:$event"
            },
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(PlaybackEpoch(1L)))

        dispatcher.drain()

        assertEquals(
            listOf(
                "start:${VoicePlaybackEvent.Active(PlaybackEpoch(1L))}",
                "end:${VoicePlaybackEvent.Active(PlaybackEpoch(1L))}",
                "start:${VoicePlaybackEvent.Drained(PlaybackEpoch(1L))}",
                "end:${VoicePlaybackEvent.Drained(PlaybackEpoch(1L))}",
                "complete",
            ),
            delivered,
        )
    }

    @Test
    fun `concurrent producers preserve per-producer fifo before completion`() {
        val delivered = mutableListOf<Any>()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val producerStart = CountDownLatch(1)
        val blocker = VoicePlaybackEvent.Active(PlaybackEpoch(0L))
        val dispatcher = PlaybackEventDispatcher(
            onEvent = { event ->
                if (event == blocker) {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                }
                delivered += event
            },
            onFailure = { _, failure -> throw AssertionError(failure) },
        )
        dispatcher.enqueue(blocker)
        val drainThread = thread(name = "playback-dispatch-drain") { dispatcher.drain() }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

        val producerCount = 4
        val eventsPerProducer = 25
        val producers = (0 until producerCount).map { producer ->
            thread(name = "playback-producer-$producer") {
                assertTrue(producerStart.await(5, TimeUnit.SECONDS))
                repeat(eventsPerProducer) { index ->
                    val epoch = PlaybackEpoch(1L + producer * eventsPerProducer + index)
                    dispatcher.enqueue(VoicePlaybackEvent.Active(epoch))
                }
            }
        }
        producerStart.countDown()
        producers.forEach { it.join(5_000L) }
        assertTrue(producers.none(Thread::isAlive))
        dispatcher.drainThrough { delivered += "complete" }
        releaseCallback.countDown()
        drainThread.join(5_000L)

        assertTrue(!drainThread.isAlive)
        assertEquals("complete", delivered.last())
        assertEquals(1 + producerCount * eventsPerProducer + 1, delivered.size)
        val deliveredEpochs = delivered
            .filterIsInstance<VoicePlaybackEvent.Active>()
            .map { it.playbackEpoch.value }
        repeat(producerCount) { producer ->
            val producerEpochs = deliveredEpochs.filter { epoch ->
                epoch in (1L + producer * eventsPerProducer)..((producer + 1L) * eventsPerProducer)
            }
            assertEquals(
                (1L + producer * eventsPerProducer..(producer + 1L) * eventsPerProducer).toList(),
                producerEpochs,
            )
        }
    }

    @Test
    fun `cross-thread reentrant callback does not hold queue lock`() {
        val delivered = mutableListOf<VoicePlaybackEvent>()
        val failures = mutableListOf<Throwable>()
        val crossThreadEnqueued = CountDownLatch(1)
        lateinit var dispatcher: PlaybackEventDispatcher
        dispatcher = PlaybackEventDispatcher(
            onEvent = { event ->
                delivered += event
                if (event == VoicePlaybackEvent.Active(PlaybackEpoch(1L))) {
                    val producer = thread(name = "playback-callback-producer") {
                        dispatcher.enqueue(VoicePlaybackEvent.Drained(PlaybackEpoch(1L)))
                        crossThreadEnqueued.countDown()
                    }
                    assertTrue(crossThreadEnqueued.await(5, TimeUnit.SECONDS))
                    dispatcher.drain()
                    producer.join(5_000L)
                    assertTrue(!producer.isAlive)
                }
            },
            onFailure = { _, failure -> failures += failure },
        )
        dispatcher.enqueue(VoicePlaybackEvent.Active(PlaybackEpoch(1L)))

        dispatcher.drain()

        assertTrue(failures.isEmpty())
        assertEquals(
            listOf(VoicePlaybackEvent.Active(PlaybackEpoch(1L)), VoicePlaybackEvent.Drained(PlaybackEpoch(1L))),
            delivered,
        )
    }
}
