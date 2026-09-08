package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `stop does not resume queued approval when predecessor cancels immediately`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            var saved = false
            val first = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(first)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { saved = true }
            }
            session.setJob(second, cancelPrevious = false)
            session.cancelJobs().forEach { it.join() }
            assertFalse(saved)
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `queued approvals preserve a decision while its save is suspended`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val completions = mutableListOf<Throwable?>()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {},
            { _, cause -> completions.add(cause) })
        try {
            val save = CompletableDeferred<Unit>()
            val decisions = mutableListOf<String>()
            val first = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(null) {
                    save.await()
                    decisions.add("A")
                }
            }
            session.setJob(first, cancelPrevious = false)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { decisions.add("B") }
            }
            session.setJob(second, cancelPrevious = false)
            assertTrue(first.isActive)
            assertTrue(decisions.isEmpty())
            save.complete(Unit)
            second.join()
            assertEquals(listOf("A", "B"), decisions)
            assertEquals(listOf<Throwable?>(null), completions)
            assertNull(session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `stopping queued approvals waits for all predecessors to stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            val cleanup = CompletableDeferred<Unit>()
            val first = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(null) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                    }
                }
            }
            session.setJob(first, cancelPrevious = false)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { error("Must not execute") }
            }
            session.setJob(second, cancelPrevious = false)
            val third = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(second) { error("Must not execute") }
            }
            session.setJob(third, cancelPrevious = false)
            val stopped = session.cancelJobs()
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
            assertFalse(third.isCompleted)
            cleanup.complete(Unit)
            stopped.forEach { it.join() }
            assertTrue(third.isCompleted)
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
            assertNull(session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `replaced job finishing late cannot clear successor or advance queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completions = mutableListOf<Throwable?>()
        val id = Uuid.random()
        val session = ConversationSession(
            id,
            Conversation.ofId(id),
            scope,
            {},
            { _, cause -> completions.add(cause) })
        try {
            val releaseOld = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<Unit>()
            val old = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseOld.await() }
                }
            }
            session.setJob(old)
            val successor = scope.launch(start = CoroutineStart.LAZY) { releaseNew.await() }
            session.setJob(successor)

            releaseOld.complete(Unit)
            old.join()
            assertSame(successor, session.getJob())
            assertTrue(completions.isEmpty())

            releaseNew.complete(Unit)
            successor.join()
            assertNull(session.getJob())
            assertEquals(listOf<Throwable?>(null), completions)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `instant completion does not leave a stale generation job`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            session.setJob(scope.launch(start = CoroutineStart.LAZY) {})
            assertNull(session.getJob())
            assertFalse(session.isGenerating)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `pending messages retain session even when queue is paused and page has no references`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            assertFalse(session.isInUse)
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
            session.messageQueue.pause()
            assertTrue(session.isInUse)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
