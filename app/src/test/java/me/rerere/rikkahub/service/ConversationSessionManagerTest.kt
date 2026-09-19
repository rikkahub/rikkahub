package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionManagerTest {
    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completions = mutableListOf<ConversationSession>()
        val manager = ConversationSessionManager(
            scope = scope,
            createInitialConversation = { Conversation.ofId(it) },
            onGenerationFinished = { session, _ -> completions.add(session) },
        )

        fun close() {
            manager.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `same conversation shares a session while different conversations stay isolated`() {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val first = rig.manager.getOrCreate(id)
            val other = rig.manager.getOrCreate(Uuid.random())
            first.updateConversation(first.state.value.copy(title = "first"))
            assertSame(first, rig.manager.getOrCreate(id))
            assertNotSame(first, other)
            assertEquals("", other.state.value.title)
            assertEquals(2, rig.manager.snapshot().size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `idle removal waits for every UI or SSE reference to be released`() {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val session = rig.manager.acquire(id)
            assertSame(session, rig.manager.acquire(id))
            rig.manager.release(id)
            rig.manager.removeIfIdle(session)
            assertSame(session, rig.manager.get(id))
            rig.manager.release(id)
            rig.manager.removeIfIdle(session)
            assertNull(rig.manager.get(id))

            val replacement = rig.manager.getOrCreate(id)
            rig.manager.removeIfIdle(session)
            assertSame(replacement, rig.manager.get(id))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `cancelled scoped operation releases its session reference`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val operation = rig.manager.launchWithSession(id) { awaitCancellation() }
            val session = requireNotNull(rig.manager.get(id))
            assertTrue(session.isInUse)
            operation.cancel()
            operation.join()
            assertFalse(session.isInUse)
            rig.manager.removeIfIdle(session)
            assertNull(rig.manager.get(id))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `generation and queued messages retain sessions after observers leave`() {
        val rig = Rig()
        try {
            val session = rig.manager.getOrCreate(Uuid.random())
            val job = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(job)
            rig.manager.removeIfIdle(session)
            assertSame(session, rig.manager.get(session.id))
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
            job.cancel()
            session.messageQueue.pause()
            rig.manager.removeIfIdle(session)
            assertSame(session, rig.manager.get(session.id))
            assertEquals(listOf(session), rig.completions)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `job observer follows sessions created and recreated after subscription`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val observed = mutableListOf<Job?>()
            val observer = rig.scope.launch {
                rig.manager.getGenerationJobStateFlow(id).collect { observed.add(it) }
            }
            withTimeout(2_000) {
                rig.manager.getGenerationJobStateFlow(id).first { it == null }
                val first = rig.manager.getOrCreate(id)
                val firstJob = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                first.setJob(firstJob)
                rig.manager.getGenerationJobStateFlow(id).first { it === firstJob }
                assertSame(firstJob, observed.last())

                firstJob.cancel()
                firstJob.join()
                rig.manager.removeIfIdle(first)
                assertNull(observed.last())

                val second = rig.manager.getOrCreate(id)
                val secondJob = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                second.setJob(secondJob)
                rig.manager.getGenerationJobStateFlow(id).first { it === secondJob }
                assertSame(secondJob, observed.last())
                assertNotSame(first, second)
            }
            observer.cancel()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `cleanup clears observed job registry and cancels every generation`() = runBlocking {
        val rig = Rig()
        try {
            val jobs = mutableMapOf<Uuid, Job?>()
            var observed: Map<Uuid, Job?> = emptyMap()
            val observer = rig.scope.launch {
                rig.manager.getConversationJobs().collect { observed = it }
            }
            repeat(2) {
                val session = rig.manager.getOrCreate(Uuid.random())
                val job = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                jobs[session.id] = job
                session.setJob(job)
            }
            assertEquals(jobs, observed)
            rig.manager.cleanup()
            assertTrue(observed.isEmpty())
            assertTrue(rig.manager.snapshot().isEmpty())
            jobs.values.forEach { assertTrue(it!!.isCancelled) }
            observer.cancel()
        } finally {
            rig.close()
        }
    }
}
