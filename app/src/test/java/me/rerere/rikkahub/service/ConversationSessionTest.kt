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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
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
