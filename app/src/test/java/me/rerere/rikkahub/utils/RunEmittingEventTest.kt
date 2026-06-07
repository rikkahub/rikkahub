package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * stale lambda. The fix routes results through a one-shot event transport collected with lifecycle.
 *
 * The behavioral invariant this guards — and the exact reason a test on the old code would fail — is:
 *   - success: [block] sends its own success event (here a literal sealed event).
 *   - non-cancellation failure: the escaping throwable becomes a failure event via onError.
 *   - cancellation: rethrown, and NO failure event is sent (teardown is not a failed operation).
 *   - data-loss: an event produced while NO collector is subscribed (the UI's `LaunchedEffect`
 *     collector is torn down whenever the screen leaves composition) must still reach a late
 *     collector. A no-replay SharedFlow DROPS it; a buffered Channel retains it. The two
 *     `survives a window with no active collector` cases pin this and fail on the old SharedFlow
 *     transport (the late collector times out because the value was dropped).
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

    private fun newEvents() = Channel<Event>(Channel.BUFFERED)

    @Test
    fun `success path emits the block's own event`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        coroutineScope {
            val collector = launch { events.receiveAsFlow().collect { collected.add(it) } }
            yield() // let the collector subscribe before any emission
            runEmitting(
                events = events,
                onError = { Event.Failed(it.message ?: "unknown") },
            ) {
                events.send(Event.Done("my-skill"))
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
            val collector = launch { events.receiveAsFlow().collect { collected.add(it) } }
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
        var rethrown = false
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
        assertTrue("CancellationException must propagate, not be swallowed", rethrown)
        assertTrue("cancellation must not emit a failure event", events.tryReceive().isFailure)
    }

    @Test
    fun `failure event survives a window with no active collector`() = runBlocking {
        // Models the data-loss bug: the screen has left composition (LaunchedEffect collector torn
        // down) while the IO operation finishes. The error event is produced with NO subscriber. A
        // no-replay SharedFlow would drop it; a buffered Channel must retain it for a late collector.
        val events = newEvents()
        runEmitting(
            events = events,
            onError = { Event.Failed(it.message ?: "unknown") },
        ) {
            throw IOException("disk full")
        }
        val received = withTimeout(1_000) { events.receive() }
        assertEquals(Event.Failed("disk full"), received)
    }

    @Test
    fun `success event survives a window with no active collector`() = runBlocking {
        // Same no-collector window as above, but on the success path: a save/import that completes
        // after the screen leaves composition must still deliver its terminal event to a late collector.
        val events = newEvents()
        runEmitting(
            events = events,
            onError = { Event.Failed(it.message ?: "unknown") },
        ) {
            events.send(Event.Done("late-skill"))
        }
        val received = withTimeout(1_000) { events.receive() }
        assertEquals(Event.Done("late-skill"), received)
    }

    @Test
    fun `bare CancellationException also rethrows without a failure event`() = runBlocking {
        val events = newEvents()
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
        assertTrue(events.tryReceive().isFailure)
    }
}
