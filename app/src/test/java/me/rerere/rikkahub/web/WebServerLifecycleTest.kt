package me.rerere.rikkahub.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
}
