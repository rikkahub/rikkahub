package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Pure-JVM regression test for [runEmitting], the cancellation-vs-error event classifier that backs
 * [launchEmitting]. The VMs it serves (SkillsVM / SkillDetailVM) used to deliver results through a
 * Composable-captured `onResult` callback after long IO work; a disposed screen left the VM calling a
 * stale lambda. The fix routes results through a one-shot SharedFlow collected with lifecycle.
 *
 * The behavioral invariant this guards — and the exact reason a test on the old code would fail — is:
 *   - success: [block] emits its own success event (here a literal sealed event).
 *   - non-cancellation failure: the escaping throwable becomes a failure event via onError, and that
 *     event is delivered RELIABLY (suspending emit) even when the flow's buffer slot is already full.
 *   - cancellation: rethrown, and NO failure event is emitted (teardown is not a failed operation).
 *
 * runEmitting is exercised directly (not through viewModelScope) precisely so the test needs no
 * coroutines-test / Robolectric — viewModelScope binds Dispatchers.Main which is unavailable on the
 * plain JVM. launchEmitting is a one-line `viewModelScope.launch { runEmitting(...) }` wrapper over it.
 */
class RunEmittingEventTest {

    private sealed interface Event {
        data class Done(val name: String) : Event
        data class Failed(val message: String) : Event
    }

    private class JobCancelled : CancellationException("job cancelled")

    private fun newEvents() = MutableSharedFlow<Event>(extraBufferCapacity = 1)

    @Test
    fun `success path emits the block's own event`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield() // let the collector subscribe before any emission
            runEmitting(
                events = events,
                onError = { Event.Failed(it.message ?: "unknown") },
            ) {
                events.tryEmit(Event.Done("my-skill"))
            }
            yield()
            collector.cancel()
        }
        assertEquals(listOf(Event.Done("my-skill")), collected)
    }

    @Test
    fun `non-cancellation failure emits a failure event via onError`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield()
            runEmitting(
                events = events,
                onError = { Event.Failed(it.message ?: "unknown") },
            ) {
                throw IOException("disk full")
            }
            yield()
            collector.cancel()
        }
        assertEquals(listOf(Event.Failed("disk full")), collected)
    }

    @Test
    fun `cancellation rethrows and emits no failure event`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        var rethrown = false
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield()
            try {
                runEmitting(
                    events = events,
                    onError = { Event.Failed("should never be emitted on cancellation") },
                ) {
                    throw JobCancelled()
                }
            } catch (e: CancellationException) {
                rethrown = true
            }
            yield()
            collector.cancel()
        }
        assertTrue("CancellationException must propagate, not be swallowed", rethrown)
        assertTrue("cancellation must not emit a failure event", collected.isEmpty())
    }

    @Test
    fun `failure event is delivered even when the buffer slot is already full`() = runBlocking {
        // Mirrors SkillsVM/SkillDetailVM: MutableSharedFlow(extraBufferCapacity = 1, SUSPEND).
        // A busy collector leaves the single buffer slot full when the error path runs. With the old
        // non-suspending tryEmit, the failure event — the ONE path that must be reliable — is silently
        // dropped (tryEmit returns false). Suspending emit instead waits for the collector to drain,
        // so the failure is never lost.
        val events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
        val collected = mutableListOf<Event>()
        val gate = CompletableDeferred<Unit>()

        coroutineScope {
            // Collector takes the first value then blocks on the gate, modelling a busy UI collector.
            val collector = launch {
                events.collect { event ->
                    collected.add(event)
                    if (event == Event.Done("processing")) gate.await()
                }
            }
            yield() // let the collector subscribe

            // First value is handed to the (now-busy) collector; the second fills the single buffer slot.
            events.emit(Event.Done("processing"))
            events.emit(Event.Done("buffered"))

            // Buffer is full and the collector is blocked: the error emission must suspend, not drop.
            val producer = launch {
                runEmitting(
                    events = events,
                    onError = { Event.Failed(it.message ?: "unknown") },
                ) {
                    throw IOException("disk full")
                }
            }
            yield()
            assertTrue("error emission must still be suspended while buffer is full", producer.isActive)

            gate.complete(Unit) // release the collector; it drains the buffer and the suspended error
            producer.join()
            yield()
            yield()
            collector.cancel()
        }

        assertTrue(
            "failure event must not be dropped when the buffer was full: $collected",
            collected.contains(Event.Failed("disk full")),
        )
    }

    @Test
    fun `bare CancellationException also rethrows without a failure event`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield()
            try {
                runEmitting(
                    events = events,
                    onError = { Event.Failed("nope") },
                ) {
                    throw CancellationException()
                }
                fail("expected CancellationException to propagate")
            } catch (e: CancellationException) {
                // expected
            }
            yield()
            collector.cancel()
        }
        assertTrue(collected.isEmpty())
    }
}
