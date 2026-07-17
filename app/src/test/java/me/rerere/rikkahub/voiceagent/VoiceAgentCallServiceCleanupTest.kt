package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceAgentCallServiceCleanupTest {
    @Test
    fun `synchronous completion does not install a completed stale end job`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = VoiceAgentEndJobTracker()
        try {
            tracker.launch(scope) {}

            assertEquals(null, tracker.job)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scope cancellation cannot abandon an entered tracked operation`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = VoiceAgentEndJobTracker()
        val operationStarted = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()
        val operationFinished = CompletableDeferred<Unit>()
        try {
            tracker.launch(scope) {
                operationStarted.complete(Unit)
                releaseOperation.await()
                operationFinished.complete(Unit)
            }
            withTimeout(TEST_TIMEOUT_MS) { operationStarted.await() }
            val launchedJob = checkNotNull(tracker.job)

            scope.cancel()
            releaseOperation.complete(Unit)

            withTimeout(TEST_TIMEOUT_MS) { operationFinished.await() }
            withTimeout(TEST_TIMEOUT_MS) { launchedJob.join() }
            assertEquals(null, tracker.job)
        } finally {
            releaseOperation.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `old operation finally cannot clear a newer tracked job`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = VoiceAgentEndJobTracker()
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newerStarted = CompletableDeferred<Unit>()
        val releaseNewer = CompletableDeferred<Unit>()
        try {
            tracker.launch(scope) {
                oldStarted.complete(Unit)
                releaseOld.await()
            }
            withTimeout(TEST_TIMEOUT_MS) { oldStarted.await() }
            val oldJob = checkNotNull(tracker.job)

            tracker.clearTracking()
            tracker.launch(scope) {
                newerStarted.complete(Unit)
                releaseNewer.await()
            }
            withTimeout(TEST_TIMEOUT_MS) { newerStarted.await() }
            val newerJob = checkNotNull(tracker.job)
            assertNotSame(oldJob, newerJob)

            releaseOld.complete(Unit)
            withTimeout(TEST_TIMEOUT_MS) { oldJob.join() }
            assertSame(newerJob, tracker.job)

            releaseNewer.complete(Unit)
            withTimeout(TEST_TIMEOUT_MS) { newerJob.join() }
            assertEquals(null, tracker.job)
        } finally {
            releaseOld.complete(Unit)
            releaseNewer.complete(Unit)
            scope.cancel()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
