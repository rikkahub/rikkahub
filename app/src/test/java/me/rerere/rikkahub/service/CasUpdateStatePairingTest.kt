package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * Regression test for issue #108's SECOND race: [ChatService.updateConversationState] pairs a
 * destructive file-cleanup side effect ([ChatService.checkFilesDelete] -> `filesManager.deleteChatFiles`)
 * with a transition that may never have atomically occurred.
 *
 * The buggy shape took an atomic `old` from `getAndUpdate {}` but then read `new` from a SEPARATE,
 * non-atomic `state.value`. Under cross-dispatcher contention (e.g. a Main-thread favorite toggle racing
 * a background `regenerateAtMessage` that drops file references) another writer can swap the state between
 * those two reads, so the `(old, new)` handed to the cleanup is NOT an adjacent transition — it straddles
 * a third writer's swap, and the file diff deletes bytes the live conversation still references (dangling
 * URI / data loss).
 *
 * The fix routes [ChatService.updateConversationState] through [casUpdateState], a `compareAndSet` loop
 * that captures `old` and the `new` it installs in the SAME successful iteration and fires the side effect
 * only after that CAS succeeds. These tests drive the PRODUCTION seam [casUpdateState] (not a hand-copied
 * mirror): reverting its body to a `getAndUpdate {}` + second `state.value` read reddens
 * [every observed transition is adjacent under contention].
 *
 * The property under test is ADJACENCY of the side-effect pair, modelled with a monotonic counter so the
 * test needs no `Conversation` (whose `files` route through `android.net.Uri`, which returns null under
 * JVM unit tests). The file-URI diff itself is already pinned by [ChatServiceFileCleanupTest]; what was
 * broken — and what this pins — is WHICH pair reaches that diff.
 */
class CasUpdateStatePairingTest {

    /**
     * Single-threaded sanity: the side effect fires exactly once with the pair the CAS installed, and
     * an identity update (no change) fires nothing.
     */
    @Test
    fun `fires once with the installed pair and skips a no-op update`() {
        val state = MutableStateFlow(0)
        val transitions = mutableListOf<Pair<Int, Int>>()

        casUpdateState(state, update = { it + 1 }, onTransition = { old, new -> transitions += old to new })
        assertEquals(listOf(0 to 1), transitions)
        assertEquals(1, state.value)

        transitions.clear()
        casUpdateState(state, update = { it }, onTransition = { old, new -> transitions += old to new })
        assertTrue("a no-op update must not fire the destructive side effect", transitions.isEmpty())
        assertEquals(1, state.value)
    }

    /**
     * The lost-pairing regression. Two writer threads (modelling the Main-thread UI write and the
     * background mutation) each run many `casUpdateState` increments on the same `MutableStateFlow`,
     * recording every `(old, new)` pair their side effect observes. Each successful CAS advances the
     * counter by exactly one, so EVERY observed pair must be adjacent (`new == old + 1`) and the union of
     * all recorded `new` values must be exactly {1..N} with no duplicates — each integer installed once.
     *
     * On the buggy `getAndUpdate {}` + second-`state.value` shape the recorded `new` is a later snapshot
     * than the one paired with `old`, so pairs are non-adjacent (gap > 1) and duplicated, reddening the
     * assertions. Mirrors the daemon-thread + bounded-await idiom of [StreamingPublishAtomicityTest] so CI
     * can never hang.
     */
    @Test
    fun `every observed transition is adjacent under contention`() {
        val perWriter = 5_000
        val writers = 2
        val errors = CopyOnWriteArrayList<Throwable>()
        val observed = CopyOnWriteArrayList<Pair<Int, Int>>()

        val state = MutableStateFlow(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)

        val threads = (0 until writers).map {
            Thread {
                try {
                    start.await()
                    repeat(perWriter) {
                        casUpdateState(
                            state,
                            update = { it + 1 },
                            onTransition = { old, new -> observed += old to new },
                        )
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }.also { it.isDaemon = true }
        }

        threads.forEach { it.start() }
        start.countDown()
        assertTrue("writers did not finish in time", done.await(30, TimeUnit.SECONDS))
        threads.forEach { it.join() }

        assertTrue(
            "concurrent writers threw: ${errors.joinToString { it.javaClass.name + ": " + it.message }}",
            errors.isEmpty(),
        )

        val total = perWriter * writers
        assertEquals("every successful CAS must fire the side effect exactly once", total, observed.size)

        val nonAdjacent = observed.filter { (old, new) -> new != old + 1 }
        assertTrue(
            "the side effect saw non-adjacent pairs (old/new straddling another writer's swap): " +
                nonAdjacent.take(5),
            nonAdjacent.isEmpty(),
        )

        val installed = observed.map { it.second }.sorted()
        assertEquals(
            "each integer must be installed exactly once across all writers (no skipped/duplicated new)",
            (1..total).toList(),
            installed,
        )
        assertEquals("final state must equal the number of successful CASes", total, state.value)
    }
}
