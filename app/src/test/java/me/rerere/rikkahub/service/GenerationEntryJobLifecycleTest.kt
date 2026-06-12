package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Behavior tests for [launchGenerationEntryJob], the shared generation entry-point lifecycle
 * (audit Q1-Q3, PR #266). These exercise the REAL production seam, replacing the source-level
 * pinning that was the only option while the lifecycle was hand-rolled three times inside
 * [ChatService] (which is not JVM-instantiable).
 */
class GenerationEntryJobLifecycleTest {

    @Test
    fun `a recoverable failure is reported through onError and does not crash the scope`() = runBlocking {
        val reported = CopyOnWriteArrayList<Exception>()
        val boom = IllegalStateException("generation pipeline failed")

        val job = launchGenerationEntryJob(
            scope = this,
            previousJob = null,
            onError = { reported.add(it) },
        ) {
            throw boom
        }
        job.join()

        assertEquals(listOf<Exception>(boom), reported)
        assertFalse("a reported failure must complete the job normally", job.isCancelled)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `cancellation propagates and is never reported as an error`() = runBlocking {
        val reported = CopyOnWriteArrayList<Exception>()
        val started = AtomicBoolean(false)

        val job = launchGenerationEntryJob(
            scope = this,
            previousJob = null,
            onError = { reported.add(it) },
        ) {
            started.set(true)
            awaitCancellation()
        }
        while (!started.get()) yield()
        job.cancel()
        job.join()

        assertTrue("cancellation must propagate (job cancelled, not completed normally)", job.isCancelled)
        assertTrue("cancellation must never reach onError", reported.isEmpty())
    }

    @Test
    fun `a CancellationException thrown by the block itself is rethrown, not reported`() = runBlocking {
        val reported = CopyOnWriteArrayList<Exception>()

        val job = launchGenerationEntryJob(
            scope = this,
            previousJob = null,
            onError = { reported.add(it) },
        ) {
            throw CancellationException("cooperative cancellation surfaced by a child")
        }
        job.join()

        assertTrue("rethrown cancellation must cancel the job", job.isCancelled)
        assertTrue("cancellation must never reach onError", reported.isEmpty())
    }

    @Test
    fun `cancellation while joining the superseded job must not run the block`() = runBlocking {
        val reported = CopyOnWriteArrayList<Exception>()
        val blockRan = AtomicBoolean(false)
        val previousStarted = AtomicBoolean(false)

        // The superseded job is still finalizing, so the new entry job is suspended inside
        // previousJob.join() at the moment the user cancels it (stopGeneration / yet another
        // supersede). The cancelled join must propagate — entry logic must never run.
        val previous = launch {
            previousStarted.set(true)
            awaitCancellation()
        }
        while (!previousStarted.get()) yield()

        val job = launchGenerationEntryJob(
            scope = this,
            previousJob = previous,
            onError = { reported.add(it) },
        ) {
            blockRan.set(true)
        }
        repeat(3) { yield() } // let the entry job reach previousJob.join()
        job.cancel()
        job.join()

        assertFalse("a cancelled entry job must never run generation entry logic", blockRan.get())
        assertTrue("cancellation must propagate (job cancelled, not completed normally)", job.isCancelled)
        assertTrue("cancellation must never reach onError", reported.isEmpty())
        previous.cancel()
    }

    @Test
    fun `block waits for the superseded job - including its NonCancellable finalizer`() = runBlocking {
        val finalizerDone = AtomicBoolean(false)
        val previousStarted = AtomicBoolean(false)
        val barrierHeld = AtomicBoolean(false)

        val previous = launch {
            try {
                previousStarted.set(true)
                awaitCancellation()
            } finally {
                // Models the generation's NonCancellable persistence finalizer: work that keeps
                // running after cancel() and that the next entry must not race.
                withContext(NonCancellable) {
                    repeat(3) { yield() }
                    finalizerDone.set(true)
                }
            }
        }
        while (!previousStarted.get()) yield()
        previous.cancel()

        val job = launchGenerationEntryJob(
            scope = this,
            previousJob = previous,
            onError = { },
        ) {
            barrierHeld.set(finalizerDone.get())
        }
        job.join()

        assertTrue(
            "block must not run until the superseded job's finalizer has finished",
            barrierHeld.get()
        )
    }
}
