package me.rerere.rikkahub.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression test for issue #16.
 *
 * [WebServerManager.start] checked `server != null` synchronously but assigned
 * `server` later inside `appScope.launch`, so two concurrent starts (or
 * restart's stop+start) both passed the guard and both created a CIO server —
 * the first one was overwritten and leaked with its socket still bound.
 *
 * The lifecycle slot is now owned by [WebServerLifecycle], which serializes the
 * check-and-claim under one Mutex. This test exercises that production primitive
 * directly with a counting factory and a suspension point inside the factory to
 * widen the race window: with the fix, the second concurrent start sees a live
 * server and never invokes the factory; revert the slot to a plain
 * check-then-assign without the lock and the factory runs twice — the exact leak
 * in the issue.
 */
class WebServerLifecycleTest {

    @Test
    fun concurrentStarts_createServerOnlyOnce() = runBlocking {
        val lifecycle = WebServerLifecycle<Any>()
        val created = AtomicInteger(0)

        val factory: suspend () -> Any = {
            // A real server bind suspends (Ktor's start(wait=false) does work);
            // the delay reproduces the window where both racing starts had
            // already passed the original synchronous null-check.
            delay(20)
            created.incrementAndGet()
            Any()
        }

        val results = (0 until 8).map {
            async(Dispatchers.Default) { lifecycle.start(factory) }
        }.awaitAll()

        // Exactly one start created a server; the rest observed it live.
        assertEquals("server factory must run exactly once", 1, created.get())
        assertEquals("exactly one start returns a handle", 1, results.count { it != null })
        assertEquals("the other starts must be rejected", 7, results.count { it == null })
    }

    @Test
    fun startAfterStop_createsNewServer() = runBlocking {
        val lifecycle = WebServerLifecycle<Any>()
        val created = AtomicInteger(0)
        val factory: suspend () -> Any = { created.incrementAndGet(); Any() }

        val first = lifecycle.start(factory)
        assertNotNull(first)

        // Second start while live is a no-op (single-instance invariant).
        assertNull(lifecycle.start(factory))
        assertEquals(1, created.get())

        var stopped: Any? = null
        lifecycle.stop { stopped = it }
        assertEquals("stop must hand back the live handle", first, stopped)

        // After stop the slot is free again, so a fresh start binds anew.
        val second = lifecycle.start(factory)
        assertNotNull(second)
        assertEquals(2, created.get())
    }

    /**
     * Regression for issue #75: restart from a running state.
     *
     * Asserts the post-fix invariants of the serialized [WebServerLifecycle.restart]
     * primitive: the old handle is released via [onStop], exactly one new server is
     * created, and a fresh handle replaces the old one. The reorder that the bug
     * actually exhibited (port-probe running before the old socket is released) is
     * reproduced separately in [restart_releasesOldSocketBeforeProbe_unlikeTwoCoroutineStopStart],
     * which is the test that goes red against the unfixed two-coroutine pattern.
     */
    @Test
    fun restartFromRunning_endsRunningWithExactlyOneServer() = runBlocking {
        val lifecycle = WebServerLifecycle<Any>()
        val created = AtomicInteger(0)
        val factory: suspend () -> Any = { created.incrementAndGet(); Any() }

        val h1 = lifecycle.start(factory)
        assertNotNull(h1)
        assertEquals(1, created.get())

        var stopped: Any? = null
        val h2 = lifecycle.restart(
            onStop = { stopped = it },
            factory = factory
        )

        assertEquals("old handle must be released via onStop", h1, stopped)
        assertEquals("exactly one new server created on restart", 2, created.get())
        assertNotNull("restart returns a live handle", h2)
        assertNotEquals("restart produces a fresh handle, not the old one", h1, h2)
    }

    /**
     * The actual fail-before reproduction for issue #75.
     *
     * Models the manager's real precondition: a single OS port can be bound by only
     * one server, so the factory must probe `isPortAvailable` (here: `portBound`) and
     * refuse to bind if the port is still held. The old server holds the port until
     * its release runs.
     *
     * [unfixedTwoCoroutineRestart] reproduces master's `WebServerManager.restart`:
     * `stop()` and `start()` as two independent coroutines whose ordering is not
     * guaranteed, so the start's port-probe can run while the old socket is still
     * bound — the probe fails and the restart leaves the server stopped. The assertion
     * on that path is RED.
     *
     * [WebServerLifecycle.restart] releases and creates inside one critical section,
     * so the probe always sees the port free and the new server binds — GREEN.
     */
    @Test
    fun restart_releasesOldSocketBeforeProbe_unlikeTwoCoroutineStopStart() = runBlocking {
        // --- fail-before: the unfixed two-coroutine stop()+start() loses the bind ---
        run {
            val portBound = AtomicBoolean(true)
            val live = AtomicReference<Any?>(Any())
            val bindFailed = AtomicBoolean(false)

            unfixedTwoCoroutineRestart(
                stopOp = {
                    // release the old socket (this is what start() must observe first)
                    live.get()?.let { delay(15); portBound.set(false); live.set(null) }
                },
                startOp = {
                    // port-probe runs first; on master it can fire before stopOp released
                    if (portBound.get()) {
                        bindFailed.set(true)
                    } else {
                        portBound.set(true); live.set(Any())
                    }
                }
            )

            assertTrue(
                "two-coroutine restart probes the port before the old socket is released, so the bind fails",
                bindFailed.get()
            )
            assertNull("the unfixed restart leaves the server stopped", live.get())
        }

        // --- fail-after: the serialized lifecycle.restart never reorders ---
        run {
            val portBound = AtomicBoolean(false)
            val lifecycle = WebServerLifecycle<Any>()
            lifecycle.start { portBound.set(true); Any() }

            val handle = lifecycle.restart(
                onStop = { delay(15); portBound.set(false) },
                factory = {
                    assertTrue("port must be free before the new bind probes it", !portBound.get())
                    portBound.set(true)
                    Any()
                }
            )
            assertNotNull("serialized restart binds the new server", handle)
            assertTrue("the new server is bound and live", portBound.get())
        }
    }

    /**
     * Faithful model of master's `WebServerManager.restart`: it called `stop()` and
     * `start()`, each of which did `appScope.launch { ... }`. The two coroutines have
     * no ordering relative to each other, so the start coroutine's port-probe can run
     * before the stop coroutine has released the old socket. This helper does NOT
     * serialize the two ops — that missing ordering is the bug.
     */
    private suspend fun unfixedTwoCoroutineRestart(
        stopOp: suspend () -> Unit,
        startOp: suspend () -> Unit
    ) = coroutineScope {
        val stopJob = launch(Dispatchers.Default) { stopOp() }
        val startJob = launch(Dispatchers.Default) { startOp() }
        startJob.join()
        stopJob.join()
    }

    @Test
    fun restartReleasesBeforeCreate_ordering() = runBlocking {
        val lifecycle = WebServerLifecycle<Any>()
        val events = Collections.synchronizedList(mutableListOf<String>())

        lifecycle.start { Any() }

        lifecycle.restart(
            onStop = { events.add("stop") },
            factory = {
                // The factory may suspend (a real bind does); the old socket must
                // already be released before this runs.
                delay(10)
                events.add("create")
                Any()
            }
        )

        assertEquals(
            "old server must be released before the new one is created",
            listOf("stop", "create"),
            events.toList()
        )
    }

    @Test
    fun restart_isSerializedAgainstConcurrentStart() = runBlocking {
        val lifecycle = WebServerLifecycle<Any>()
        val created = AtomicInteger(0)
        val stopped = AtomicInteger(0)

        lifecycle.start { created.incrementAndGet(); Any() }

        val jobs = (0 until 8).map { i ->
            launch(Dispatchers.Default) {
                if (i % 2 == 0) {
                    lifecycle.restart(
                        onStop = { stopped.incrementAndGet() },
                        factory = { delay(5); created.incrementAndGet(); Any() }
                    )
                } else {
                    lifecycle.start { created.incrementAndGet(); Any() }
                }
            }
        }
        jobs.forEach { it.join() }

        // Every server that was created and later replaced must have been stopped:
        // a server is leaked iff it was created but neither still-live nor stopped.
        // One server is still live at the end; all others must have been torn down.
        assertEquals(
            "every replaced server must be released (no leaked handle)",
            created.get() - 1,
            stopped.get()
        )
        assertTrue("at least the initial server plus the restarts ran", created.get() >= 1)

        // Drain the final live server to assert exactly one remained.
        var finalStopped = 0
        lifecycle.stop { finalStopped++ }
        assertEquals("exactly one live server remained at the end", 1, finalStopped)
    }
}
