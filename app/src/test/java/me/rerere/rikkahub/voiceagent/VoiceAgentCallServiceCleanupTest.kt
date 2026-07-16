package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceAgentCallServiceCleanupTest {
    @Test
    fun `stale generation before drain entry still cleans detached session`() = runBlocking {
        var drainCalls = 0
        var completions = 0
        var endJobClears = 0

        completeVoiceAgentEndForGeneration(
            isCurrent = { false },
            endAndDrain = { drainCalls += 1 },
            onCompleted = { completions += 1 },
            stopForeground = { completions += 1 },
            stopSelf = { completions += 1 },
            clearEndJob = { endJobClears += 1 },
        )

        assertEquals(1, drainCalls)
        assertEquals(0, completions)
        assertEquals(1, endJobClears)
    }

    @Test
    fun `stale end completion cannot clean up a newer call generation`() = runBlocking {
        var currentGeneration = 1L
        var completions = 0
        var endJobClears = 0
        val drainStarted = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            completeVoiceAgentEndForGeneration(
                isCurrent = { currentGeneration == 1L },
                endAndDrain = {
                    drainStarted.complete(Unit)
                    releaseDrain.await()
                },
                onCompleted = { completions += 1 },
                stopForeground = { completions += 1 },
                stopSelf = { completions += 1 },
                clearEndJob = { endJobClears += 1 },
            )
        }
        try {
            withTimeout(CLEANUP_TIMEOUT_MS) { drainStarted.await() }
            currentGeneration = 2L
            releaseDrain.complete(Unit)
            withTimeout(CLEANUP_TIMEOUT_MS) { cleanup.await() }

            assertEquals(0, completions)
            assertEquals(1, endJobClears)
        } finally {
            releaseDrain.complete(Unit)
            cleanup.cancel()
        }
    }

    @Test
    fun `drain failure remains primary while every completion stage runs in order`() = runBlocking {
        val drainFailure = IllegalStateException("drain failed")
        val completionFailure = IllegalArgumentException("completion failed")
        val foregroundFailure = UnsupportedOperationException("foreground failed")
        val selfFailure = IllegalStateException("self failed")
        val clearFailure = IllegalStateException("clear failed")
        val events = mutableListOf<String>()

        val thrown = runCatching {
            completeVoiceAgentEndForGeneration(
                isCurrent = { true },
                endAndDrain = { events += "drain"; throw drainFailure },
                onCompleted = { events += "completed"; throw completionFailure },
                stopForeground = { events += "stopForeground"; throw foregroundFailure },
                stopSelf = { events += "stopSelf"; throw selfFailure },
                clearEndJob = { events += "clearEndJob"; throw clearFailure },
            )
        }.exceptionOrNull()

        assertSame(drainFailure, thrown)
        assertEquals(
            listOf(completionFailure, foregroundFailure, selfFailure, clearFailure),
            thrown?.suppressed?.toList(),
        )
        assertEquals(
            listOf("drain", "completed", "stopForeground", "stopSelf", "clearEndJob"),
            events,
        )
    }

    @Test
    fun `synchronous completion does not install a completed stale end job`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = VoiceAgentEndJobTracker()
        try {
            tracker.launch(scope) { clearEndJob ->
                completeVoiceAgentEndForGeneration(
                    isCurrent = { true },
                    endAndDrain = {},
                    onCompleted = {},
                    stopForeground = {},
                    stopSelf = {},
                    clearEndJob = clearEndJob,
                )
            }

            assertEquals(null, tracker.job)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scope cancellation after destruction cannot abandon entered detached cleanup`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = VoiceAgentEndJobTracker()
        val drainStarted = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var current = true

        try {
            tracker.launch(scope) { clearEndJob ->
                completeVoiceAgentEndForGeneration(
                    isCurrent = { current },
                    endAndDrain = {
                        events += "drain-started"
                        drainStarted.complete(Unit)
                        releaseDrain.await()
                        events += "drain-finished"
                    },
                    onCompleted = { events += "completed" },
                    stopForeground = { events += "stopForeground" },
                    stopSelf = { events += "stopSelf" },
                    clearEndJob = {
                        events += "clearEndJob"
                        clearEndJob()
                    },
                )
            }

            assertEquals(true, drainStarted.isCompleted)
            val launchedJob = checkNotNull(tracker.job)
            current = false
            scope.cancel()
            releaseDrain.complete(Unit)
            withTimeout(CLEANUP_TIMEOUT_MS) { launchedJob.join() }

            assertEquals(listOf("drain-started", "drain-finished", "clearEndJob"), events)
            assertEquals(null, tracker.job)
        } finally {
            releaseDrain.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `old ordered clear and finally cannot clear newer tracked job`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = VoiceAgentEndJobTracker()
        val oldDrainStarted = CompletableDeferred<Unit>()
        val releaseOldDrain = CompletableDeferred<Unit>()
        val oldOrderedClearFinished = CompletableDeferred<Unit>()
        val releaseOldFinally = CompletableDeferred<Unit>()
        val newerDrainStarted = CompletableDeferred<Unit>()
        val releaseNewerDrain = CompletableDeferred<Unit>()
        try {
            tracker.launch(scope) { clearOldEndJob ->
                completeVoiceAgentEndForGeneration(
                    isCurrent = { true },
                    endAndDrain = {
                        oldDrainStarted.complete(Unit)
                        releaseOldDrain.await()
                    },
                    onCompleted = {},
                    stopForeground = {},
                    stopSelf = {},
                    clearEndJob = clearOldEndJob,
                )
                oldOrderedClearFinished.complete(Unit)
                releaseOldFinally.await()
            }
            withTimeout(CLEANUP_TIMEOUT_MS) { oldDrainStarted.await() }
            val oldJob = checkNotNull(tracker.job)
            assertSame(oldJob, tracker.job)

            tracker.clearTracking()
            tracker.launch(scope) { clearNewerEndJob ->
                completeVoiceAgentEndForGeneration(
                    isCurrent = { true },
                    endAndDrain = {
                        newerDrainStarted.complete(Unit)
                        releaseNewerDrain.await()
                    },
                    onCompleted = {},
                    stopForeground = {},
                    stopSelf = {},
                    clearEndJob = clearNewerEndJob,
                )
            }
            withTimeout(CLEANUP_TIMEOUT_MS) { newerDrainStarted.await() }
            val newerJob = checkNotNull(tracker.job)
            assertNotSame(oldJob, newerJob)
            assertSame(newerJob, tracker.job)

            releaseOldDrain.complete(Unit)
            withTimeout(CLEANUP_TIMEOUT_MS) { oldOrderedClearFinished.await() }
            assertSame(newerJob, tracker.job)

            releaseOldFinally.complete(Unit)
            withTimeout(CLEANUP_TIMEOUT_MS) { oldJob.join() }
            assertSame(newerJob, tracker.job)

            releaseNewerDrain.complete(Unit)
            withTimeout(CLEANUP_TIMEOUT_MS) { newerJob.join() }
            assertEquals(null, tracker.job)
        } finally {
            releaseOldDrain.complete(Unit)
            releaseOldFinally.complete(Unit)
            releaseNewerDrain.complete(Unit)
            scope.cancel()
        }
    }

    private companion object {
        const val CLEANUP_TIMEOUT_MS = 2_000L
    }
}
