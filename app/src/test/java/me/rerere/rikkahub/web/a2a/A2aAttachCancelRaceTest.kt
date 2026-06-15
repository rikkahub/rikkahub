package me.rerere.rikkahub.web.a2a

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.uuid.Uuid
import org.junit.Test

private class FakeA2aMessageFlowClient : A2aMessageFlowClient {
    var sendMessageCallCount = 0

    override suspend fun sendMessageReturningJob(conversationId: Uuid, content: List<me.rerere.ai.ui.UIMessagePart>): Job {
        sendMessageCallCount++
        return Job()
    }

    override suspend fun handleToolApprovalReturningJob(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): Job? = null

    override suspend fun initializeConversationForSkill(contextId: Uuid, assistantId: Uuid) {
        // no-op for tests
    }
}

class A2aAttachCancelRaceTest {

    @Test
    fun `cancel before attachment rejects attach and never starts collector`() = runBlocking {
        val registry = A2aTaskRegistry()
        val chatService = FakeA2aMessageFlowClient()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()

        val admission = registry.admit(contextId, assistantId, "message-1") as A2aAdmission.Accepted
        var stopCount = 0

        val task = registry.requestCancelWithStop(admission.entry.taskId) { _, _ -> stopCount++ }
        assertNotNull(task)

        val job = chatService.sendMessageReturningJob(contextId, emptyList())
        var collectorStarted = false
        when (val attachResult = registry.attachJob(admission.entry.taskId, job)) {
            is A2aAttachResult.Accepted -> {
                collectorStarted = true
                registry.startCollector(admission.entry.taskId) { Job() }
            }
            is A2aAttachResult.Rejected -> {
                job.cancel()
            }
        }

        val firstTerminal = registry.transition(admission.entry.taskId, A2aTaskState.CANCELED, terminal = true)
        val secondTerminal = registry.transition(admission.entry.taskId, A2aTaskState.CANCELED, terminal = true)

        assertFalse(collectorStarted)
        assertEquals(1, chatService.sendMessageCallCount)
        assertEquals(0, stopCount)
        assertEquals(A2aTaskState.CANCELED, registry.get(admission.entry.taskId)?.state)
        assertNotNull(firstTerminal)
        assertNull(secondTerminal)
    }

    @Test
    fun `duplicate messageId during attach window never starts a second generation`() = runBlocking {
        val registry = A2aTaskRegistry()
        val chatService = FakeA2aMessageFlowClient()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()
        val sourceMessageId = "message-dup"

        val admission = registry.admit(contextId, assistantId, sourceMessageId) as A2aAdmission.Accepted
        val duplicate = registry.admit(contextId, assistantId, sourceMessageId)
        assertTrue(duplicate is A2aAdmission.Duplicate)
        assertEquals(admission.entry.taskId, (duplicate as A2aAdmission.Duplicate).existing.taskId)

        val firstJob = chatService.sendMessageReturningJob(contextId, emptyList())
        var collectorStarted = false
        val attachResult = registry.attachJob(admission.entry.taskId, firstJob)
        if (attachResult is A2aAttachResult.Accepted) {
            collectorStarted = true
            registry.startCollector(admission.entry.taskId) { Job() }
        }

        assertTrue(collectorStarted)
        assertEquals(1, chatService.sendMessageCallCount)
    }

    @Test
    fun `normal attach accepts and starts collector`() = runBlocking {
        val registry = A2aTaskRegistry()
        val chatService = FakeA2aMessageFlowClient()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()

        val admission = registry.admit(contextId, assistantId, "message-2") as A2aAdmission.Accepted
        val job = chatService.sendMessageReturningJob(contextId, emptyList())

        var collectorStarted = false
        val attachResult = registry.attachJob(admission.entry.taskId, job)
        if (attachResult is A2aAttachResult.Accepted) {
            collectorStarted = true
            registry.startCollector(admission.entry.taskId) { Job() }
        }

        assertTrue(collectorStarted)
        assertEquals(1, chatService.sendMessageCallCount)
    }
}
