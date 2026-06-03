package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Regression test for issue #15.
 *
 * McpManager keeps three shared maps (clients/reconnectJobs/reconnectAttempts)
 * that are structurally mutated (put/remove) AND iterated (.entries) concurrently
 * from many contexts: the parallel per-config add/remove launches, the
 * Dispatchers.IO add/remove/reconnect methods, and the arbitrary-thread transport
 * onClose/onError callbacks. With a plain LinkedHashMap (the original
 * `mutableMapOf()`) this is undefined behavior: ConcurrentModificationException
 * from the `.entries` iteration, or lost entries from racing put/remove — the
 * latter silently drops a live Client and leaks its open transport (the P1 leak
 * in the issue).
 *
 * This test exercises the EXACT production access pattern against a map obtained
 * the SAME way the production fields are initialized (`newMcpConcurrentMap()`),
 * so it is bound to the production declaration choice: revert the factory to
 * `mutableMapOf()` and this test fails for the exact reason in the issue; keep it
 * `ConcurrentHashMap` and it passes.
 */
class McpManagerConcurrencyTest {

    @Test
    fun sharedMap_survivesConcurrentMutationAndIteration() {
        val map: MutableMap<Int, Any> = newMcpConcurrentMap()
        val errors = CopyOnWriteArrayList<Throwable>()

        val threadCount = 8
        val iterations = 5_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        // Keys that every thread will repeatedly put/remove — mirrors the
        // production race where add/remove/reconnect touch the same config id.
        val sharedKeys = (0 until 16).toList()

        val threads = (0 until threadCount).map { t ->
            Thread {
                try {
                    start.await()
                    repeat(iterations) { i ->
                        // addClient: clients[config] = client
                        map[sharedKeys[i % sharedKeys.size]] = Any()
                        // unique writer key (never removed) for the loss check
                        map[1_000 + t * iterations + i] = Any()
                        // removeClient: clients.remove(entry.key)
                        map.remove(sharedKeys[(i + 1) % sharedKeys.size])
                        // getClient/callTool/removeClient: clients.entries iteration
                        for (e in map.entries) {
                            e.value
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }.also { it.isDaemon = true; it.start() }
        }

        start.countDown()
        assertTrue("threads did not finish in time", done.await(60, TimeUnit.SECONDS))
        threads.forEach { it.join() }

        // No CME / NPE / bucket-corruption from concurrent structural mutation.
        assertTrue(
            "concurrent access threw: ${errors.joinToString { it.javaClass.name + ": " + it.message }}",
            errors.isEmpty()
        )

        // No silently dropped entries: every unique writer key must survive.
        val expectedUniqueKeys = threadCount * iterations
        val survivingUniqueKeys = map.keys.count { it >= 1_000 }
        assertEquals(
            "entries were silently lost under concurrent mutation",
            expectedUniqueKeys,
            survivingUniqueKeys
        )
    }
}
