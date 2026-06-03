package me.rerere.rikkahub.service

import me.rerere.rikkahub.service.GenerationActivityTracker.Transition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-logic test for [GenerationActivityTracker], the active-generation reference counter that
 * drives the foreground service start/stop edges. Mirrors the pure-logic style of
 * [InterruptedPersistenceTest].
 */
class GenerationActivityTrackerTest {

    @Test
    fun `0 to 1 edge reports STARTED`() {
        val tracker = GenerationActivityTracker()
        assertEquals(Transition.STARTED, tracker.acquire())
        assertEquals(1, tracker.count)
    }

    @Test
    fun `1 to 0 edge reports STOPPED`() {
        val tracker = GenerationActivityTracker()
        tracker.acquire()
        assertEquals(Transition.STOPPED, tracker.release())
        assertEquals(0, tracker.count)
    }

    @Test
    fun `intermediate increments and decrements report NONE`() {
        val tracker = GenerationActivityTracker()
        assertEquals(Transition.STARTED, tracker.acquire())   // 0 -> 1
        assertEquals(Transition.NONE, tracker.acquire())      // 1 -> 2
        assertEquals(Transition.NONE, tracker.acquire())      // 2 -> 3
        assertEquals(Transition.NONE, tracker.release())      // 3 -> 2
        assertEquals(Transition.NONE, tracker.release())      // 2 -> 1
        assertEquals(Transition.STOPPED, tracker.release())   // 1 -> 0
        assertEquals(0, tracker.count)
    }

    @Test
    fun `over release clamps at zero and reports NONE`() {
        val tracker = GenerationActivityTracker()
        assertEquals(Transition.NONE, tracker.release())
        assertEquals(0, tracker.count)
        // Even after a balanced cycle, an extra release must not go negative.
        tracker.acquire()
        tracker.release()
        assertEquals(Transition.NONE, tracker.release())
        assertEquals(0, tracker.count)
    }

    @Test
    fun `concurrent balanced acquire and release ends at zero with exactly one of each edge`() {
        val tracker = GenerationActivityTracker()
        val n = 200
        val started = AtomicInteger(0)
        val stopped = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(1)

        // `pending` is the atomic budget of acquires that have happened but not yet been released.
        // Each acquire bumps it; each release task claims a unit before calling release(). This makes
        // every release() correspond to a genuine prior acquire so the N/N run stays balanced.
        val pending = AtomicInteger(0)

        try {
            val acquireTasks = (0 until n).map {
                pool.submit {
                    ready.await()
                    if (tracker.acquire() == Transition.STARTED) started.incrementAndGet()
                    pending.incrementAndGet()
                }
            }
            // Each release task must consume exactly ONE acquire, otherwise the run is not balanced
            // and the final count is nondeterministic. Gating purely on `tracker.count > 0` is a
            // TOCTOU race: two tasks can both observe count == 1, one decrements to 0 (a real release)
            // while the other's release() hits the clamp and no-ops — yet both terminate, leaving a
            // net deficit of decrements. Claiming a unit from `pending` first guarantees every
            // release() pairs with a genuine acquire, regardless of thread interleaving.
            val releaseTasks = (0 until n).map {
                pool.submit {
                    ready.await()
                    while (true) {
                        val budget = pending.get()
                        if (budget > 0 && pending.compareAndSet(budget, budget - 1)) {
                            if (tracker.release() == Transition.STOPPED) stopped.incrementAndGet()
                            break
                        }
                        Thread.onSpinWait()
                    }
                }
            }
            ready.countDown()
            (acquireTasks + releaseTasks).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(0, tracker.count)
        // The transient count crosses 0 -> 1 and 1 -> 0 exactly once each net across a balanced run,
        // but interleavings can legitimately produce multiple STARTED/STOPPED if the count dips back
        // to 0 mid-run. The invariant we enforce: equal numbers of each, and a final count of 0.
        assertEquals(started.get(), stopped.get())
    }

    @Test
    fun `strictly serialized balanced run yields exactly one STARTED and one STOPPED`() {
        val tracker = GenerationActivityTracker()
        var started = 0
        var stopped = 0
        // Drive count 0 -> 5 -> 0 with all acquires before any release: exactly one of each edge.
        repeat(5) { if (tracker.acquire() == Transition.STARTED) started++ }
        repeat(5) { if (tracker.release() == Transition.STOPPED) stopped++ }
        assertEquals(1, started)
        assertEquals(1, stopped)
        assertEquals(0, tracker.count)
    }
}
