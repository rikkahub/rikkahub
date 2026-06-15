package me.rerere.rikkahub.web.a2a

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.uuid.Uuid
import org.junit.Test

class A2aApprovalReattachRaceTest {

    @Test
    fun `old completion job completion does not terminalize after reattach and current job does`() = runBlocking {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        val collector = registry.startCollector(entry.taskId) { Job() }
        val oldJob = Job()
        val newJob = Job()
        val reconciledCompletionJobs = Collections.newSetFromMap(IdentityHashMap<Job, Boolean>())

        val transitionHistory = mutableListOf<A2aTaskState>()
        val emitTerminalTransition: suspend (A2aTaskState) -> Unit = { state ->
            val transitioned = registry.transition(entry.taskId, state, terminal = true)
            if (transitioned != null) {
                transitionHistory += state
                if (state == A2aTaskState.COMPLETED || state == A2aTaskState.CANCELED || state == A2aTaskState.FAILED) {
                    registry.cancelCollector(entry.taskId)
                }
            }
        }

        check(registry.attachJob(entry.taskId, oldJob) is A2aAttachResult.Accepted)
        entry.state = A2aTaskState.INPUT_REQUIRED

        // Approval re-attaches a new job while the old job is still racing for completion.
        check(registry.attachJob(entry.taskId, newJob) is A2aAttachResult.Accepted)
        entry.state = A2aTaskState.WORKING

        reconcileTerminalFromJobCompletion(
            entry = entry,
            completedJob = oldJob,
            reconciledCompletionJobs = reconciledCompletionJobs,
            classifyTerminalState = { A2aTaskState.COMPLETED },
            emitTerminalTransition = emitTerminalTransition,
        )

        assertEquals(A2aTaskState.WORKING, entry.state)
        assertFalse(collector.isCancelled)
        assertTrue(transitionHistory.isEmpty())
        assertEquals(entry.taskId, registry.activeByContext[entry.contextId])

        reconcileTerminalFromJobCompletion(
            entry = entry,
            completedJob = newJob,
            reconciledCompletionJobs = reconciledCompletionJobs,
            classifyTerminalState = { A2aTaskState.COMPLETED },
            emitTerminalTransition = emitTerminalTransition,
        )

        assertEquals(A2aTaskState.COMPLETED, entry.state)
        assertTrue(collector.isCancelled)
        assertEquals(listOf(A2aTaskState.COMPLETED), transitionHistory)
        assertEquals(null, registry.activeByContext[entry.contextId])
    }
}
