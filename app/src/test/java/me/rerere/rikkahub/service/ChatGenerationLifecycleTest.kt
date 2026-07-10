package me.rerere.rikkahub.service

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatGenerationLifecycleTest {
    private val terminalEvent = AppEvent.ChatGenerationEnded(
        conversationId = Uuid.random(),
        senderName = "Assistant",
        contentPreview = null,
    )

    @Test
    fun `cancellation waits for saturated bus and emits terminal event exactly once`() = runBlocking {
        val fixture = SaturatedEventBusFixture(this)
        fixture.saturate()
        try {
            val started = CompletableDeferred<Unit>()
            val generation = launch {
                withGuaranteedChatGenerationEnd(fixture.bus, { terminalEvent }) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }

            started.await()
            generation.cancel()
            yield()
            assertFalse(generation.isCompleted)

            fixture.releaseCollector()
            generation.join()
            fixture.awaitTerminal()

            assertTrue(generation.isCancelled)
            assertEquals(listOf(terminalEvent), fixture.terminalEvents())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `failure is preserved after saturated bus receives one terminal event`() = runBlocking {
        supervisorScope {
            val fixture = SaturatedEventBusFixture(this)
            fixture.saturate()
            val expected = IllegalStateException("generation failed")
            try {
                val generation = async {
                    withGuaranteedChatGenerationEnd(fixture.bus, { terminalEvent }) {
                        throw expected
                    }
                }

                yield()
                assertFalse(generation.isCompleted)

                fixture.releaseCollector()
                val actual = runCatching { generation.await() }.exceptionOrNull()
                fixture.awaitTerminal()

                assertEquals(expected::class, actual?.let { it::class })
                assertEquals(expected.message, actual?.message)
                assertEquals(listOf(terminalEvent), fixture.terminalEvents())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `success emits terminal event exactly once with final preview`() = runBlocking {
        val bus = AppEventBus()
        val terminalSeen = CompletableDeferred<AppEvent.ChatGenerationEnded>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { event ->
                if (event is AppEvent.ChatGenerationEnded) terminalSeen.complete(event)
            }
        }
        val completedEvent = terminalEvent.copy(contentPreview = "done")

        try {
            val result = withGuaranteedChatGenerationEnd(bus, { completedEvent }) { "result" }

            assertEquals("result", result)
            assertEquals(completedEvent, terminalSeen.await())
        } finally {
            collector.cancelAndJoin()
        }
    }

    private class SaturatedEventBusFixture(scope: kotlinx.coroutines.CoroutineScope) {
        val bus = AppEventBus()
        private val release = CompletableDeferred<Unit>()
        private val terminalSeen = CompletableDeferred<Unit>()
        private val terminals = Collections.synchronizedList(mutableListOf<AppEvent.ChatGenerationEnded>())
        private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { event ->
                release.await()
                if (event is AppEvent.ChatGenerationEnded) {
                    terminals += event
                    terminalSeen.complete(Unit)
                }
            }
        }

        suspend fun saturate() {
            yield()
            bus.emit(AppEvent.Speak("held"))
            yield()
            repeat(16) { index ->
                check(bus.tryEmit(AppEvent.Speak("buffered-$index")))
            }
            check(!bus.tryEmit(AppEvent.Speak("overflow")))
        }

        fun releaseCollector() {
            release.complete(Unit)
        }

        suspend fun awaitTerminal() {
            terminalSeen.await()
        }

        fun terminalEvents(): List<AppEvent.ChatGenerationEnded> = terminals.toList()

        suspend fun close() {
            release.complete(Unit)
            collector.cancelAndJoin()
        }
    }
}
