package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `lazy job starts only after installation and leases use monotonic epochs`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val firstSawInstalledBoundary = CompletableDeferred<Boolean>()
        lateinit var firstJob: Job
        firstJob = scope.launch(start = CoroutineStart.LAZY) {
            firstSawInstalledBoundary.complete(session.getJob() === firstJob)
            awaitCancellation()
        }

        assertFalse(firstJob.isActive)
        val firstLease = session.install(firstJob)

        assertFalse(firstJob.isActive)
        assertFalse(firstSawInstalledBoundary.isCompleted)
        assertTrue(firstJob.start())
        assertTrue(firstSawInstalledBoundary.await())
        assertSame(firstJob, firstLease.job)
        assertTrue(session.isCurrent(firstLease))

        val secondJob = lazyJob(scope)
        val secondLease = session.install(secondJob)

        assertTrue(secondLease.epoch > firstLease.epoch)
        assertFalse(session.isCurrent(firstLease))
        assertTrue(session.isCurrent(secondLease))
        assertFalse(secondJob.isActive)
        assertTrue(secondJob.start())
        session.cleanup()
    }

    @Test
    fun `lease binds once to its own run identity`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val firstLease = session.install(lazyJob(scope))
        assertTrue(firstLease.job.start())

        assertTrue(session.isCurrent(firstLease))
        assertFalse(session.isCurrent(firstLease, "run-a"))
        assertTrue(session.bindRun(firstLease, "run-a"))
        assertTrue(session.bindRun(firstLease, "run-a"))
        assertFalse(session.bindRun(firstLease, "run-b"))
        assertTrue(session.isCurrent(firstLease, "run-a"))
        assertFalse(session.isCurrent(firstLease, "run-b"))
        assertSame(firstLease.job, session.jobForRun("run-a"))

        val secondLease = session.install(lazyJob(scope))
        assertTrue(secondLease.job.start())
        assertFalse(session.bindRun(firstLease, "run-a"))
        assertTrue(session.bindRun(secondLease, "run-b"))
        assertNull(session.jobForRun("run-a"))
        assertSame(secondLease.job, session.jobForRun("run-b"))

        val staleEpochForCurrentJob = GenerationLease(firstLease.epoch, secondLease.job)
        assertFalse(session.isCurrent(staleEpochForCurrentJob, "run-b"))
        assertFalse(session.bindRun(staleEpochForCurrentJob, "run-b"))
        session.cleanup()
    }

    @Test
    fun `old completion and stale stop cannot clear or cancel the newer generation`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val firstLease = session.install(lazyJob(scope))
        assertTrue(firstLease.job.start())
        assertTrue(session.bindRun(firstLease, "run-a"))
        val completionSawOldAsCurrent = CompletableDeferred<Boolean>()
        firstLease.job.invokeOnCompletion {
            completionSawOldAsCurrent.complete(session.isCurrent(firstLease, "run-a"))
        }

        val secondLease = session.install(lazyJob(scope))
        assertTrue(secondLease.job.start())
        assertTrue(session.bindRun(secondLease, "run-b"))

        assertFalse(completionSawOldAsCurrent.await())
        assertTrue(session.isCurrent(secondLease, "run-b"))
        assertSame(secondLease.job, session.getJob())
        session.jobForRun("run-a")?.cancel()
        assertTrue(secondLease.job.isActive)
        assertSame(secondLease.job, session.jobForRun("run-b"))
        session.cleanup()
    }

    @Test
    fun `cleanup invalidates lease before cancelling job and preserves epoch monotonicity`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val lease = session.install(lazyJob(scope))
        assertTrue(lease.job.start())
        assertTrue(session.bindRun(lease, "run-a"))
        val completionSawLeaseAsCurrent = CompletableDeferred<Boolean>()
        lease.job.invokeOnCompletion {
            completionSawLeaseAsCurrent.complete(session.isCurrent(lease, "run-a"))
        }

        session.cleanup()

        assertFalse(completionSawLeaseAsCurrent.await())
        assertFalse(session.isCurrent(lease))
        assertTrue(lease.job.isCancelled)
        assertNull(session.getJob())
        assertNull(session.jobForRun("run-a"))

        val nextLease = session.install(lazyJob(scope))
        assertTrue(nextLease.epoch > lease.epoch)
        session.cleanup()
    }

    @Test
    fun `conditional install succeeds only for the unchanged epoch and does not start the job`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val expectedEpoch = session.epochToken()
        val bodyStarted = CompletableDeferred<Unit>()
        val candidate = scope.launch(start = CoroutineStart.LAZY) {
            bodyStarted.complete(Unit)
            awaitCancellation()
        }

        val lease = session.installIfEpoch(candidate, expectedEpoch)
            ?: error("Unchanged epoch must install")

        assertTrue(lease.epoch > expectedEpoch)
        assertEquals(lease.epoch, session.epochToken())
        assertSame(candidate, session.getJob())
        assertTrue(session.isCurrent(lease))
        assertFalse(candidate.isActive)
        assertFalse(bodyStarted.isCompleted)
        assertTrue(candidate.start())
        assertTrue(bodyStarted.isCompleted)
        session.cleanup()
    }

    @Test
    fun `stale approval cannot replace or cancel a newer send and leaves its job untouched`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val approvalEpoch = session.epochToken()
        val newerLease = session.install(lazyJob(scope))
        assertTrue(newerLease.job.start())
        val staleApprovalJob = lazyJob(scope)

        val staleLease = session.installIfEpoch(staleApprovalJob, approvalEpoch)

        assertNull(staleLease)
        assertFalse(staleApprovalJob.isActive)
        assertFalse(staleApprovalJob.isCompleted)
        assertFalse(staleApprovalJob.isCancelled)
        assertTrue(session.isCurrent(newerLease))
        assertTrue(newerLease.job.isActive)
        assertSame(newerLease.job, session.getJob())
        assertEquals(newerLease.epoch, session.epochToken())
        staleApprovalJob.cancel()
        session.cleanup()
    }

    @Test
    fun `concurrent conditional installs allow exactly one winner`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val expectedEpoch = session.epochToken()
        val firstJob = lazyJob(scope)
        val secondJob = lazyJob(scope)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<GenerationLease?> {
                ready.countDown()
                start.await()
                session.installIfEpoch(firstJob, expectedEpoch)
            }
            val second = executor.submit<GenerationLease?> {
                ready.countDown()
                start.await()
                session.installIfEpoch(secondJob, expectedEpoch)
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val results = listOf(
                first.get(5, TimeUnit.SECONDS),
                second.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, results.count { it != null })
            val winner = results.filterNotNull().single()
            val loser = if (winner.job === firstJob) secondJob else firstJob
            assertTrue(session.isCurrent(winner))
            assertSame(winner.job, session.getJob())
            assertEquals(winner.epoch, session.epochToken())
            assertFalse(loser.isActive)
            assertFalse(loser.isCompleted)
            assertFalse(loser.isCancelled)
            loser.cancel()
        } finally {
            session.cleanup()
            firstJob.cancel()
            secondJob.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun `negative conditional epoch is rejected without installing the job`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val candidate = lazyJob(scope)

        try {
            session.installIfEpoch(candidate, -1)
            fail("Negative epoch must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertNull(session.getJob())
        assertFalse(candidate.isActive)
        assertFalse(candidate.isCompleted)
        candidate.cancel()
        session.cleanup()
    }

    @Test
    fun `cleanup invalidates optimistic approval epochs`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        val staleEpoch = session.epochToken()
        val candidate = lazyJob(scope)

        session.cleanup()

        assertTrue(session.epochToken() > staleEpoch)
        assertNull(session.installIfEpoch(candidate, staleEpoch))
        assertFalse(candidate.isActive)
        candidate.cancel()
    }

    @Test
    fun `installing a replacement clears processing status immediately`() = runBlocking {
        val scope = testScope()
        val session = testSession(scope)
        session.processingStatus.value = "old generation is working"
        val candidate = lazyJob(scope)

        session.install(candidate)

        assertNull(session.processingStatus.value)
        candidate.cancel()
        session.cleanup()
    }

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun testSession(scope: CoroutineScope): ConversationSession {
        val conversationId = Uuid.random()
        return ConversationSession(
            conversationId,
            Conversation.ofId(conversationId, Uuid.random()),
            scope,
        ) {}
    }

    private fun lazyJob(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.LAZY) {
        awaitCancellation()
    }
}
