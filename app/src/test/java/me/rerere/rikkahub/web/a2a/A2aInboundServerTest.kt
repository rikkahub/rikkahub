package me.rerere.rikkahub.web.a2a

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

private class InboundFakeA2aMessageFlowClient(
    private val failOnInit: Boolean = false,
    private val failOnSend: Boolean = false,
) : A2aMessageFlowClient {
    var initializeCalls = 0
    var sendCalls = 0

    override suspend fun sendMessageReturningJob(conversationId: Uuid, content: List<UIMessagePart>): Job {
        sendCalls++
        if (failOnSend) {
            throw RuntimeException("send failed")
        }
        return Job()
    }

    override suspend fun handleToolApprovalReturningJob(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): Job? {
        return null
    }

    override suspend fun initializeConversationForSkill(contextId: Uuid, assistantId: Uuid) {
        initializeCalls++
        if (failOnInit) {
            throw RuntimeException("initialize failed")
        }
    }
}

private fun paramsFor(contextId: Uuid, skillId: String, messageId: String): MessageSendParams =
    MessageSendParams(
        contextId = contextId.toString(),
        skillId = skillId,
        message = A2aMessage(
            messageId = messageId,
            role = A2aRole.USER,
            parts = listOf(A2aPart.TextPart("hello")),
        ),
    )

class A2aInboundServerTest {

    @Test
    fun `duplicate and conflict admissions do not initialize conversations before admission`() = runBlocking {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()
        val assistant = Assistant(id = assistantId, spawnable = true)

        val accepted = registry.admit(contextId, assistantId, "message-1") as A2aAdmission.Accepted
        val flowClient = InboundFakeA2aMessageFlowClient()

        val duplicate = startOrResumeA2aTask(
            appScope = this,
            params = paramsFor(contextId, assistantId.toString(), "message-1"),
            messageFlowClient = flowClient,
            registry = registry,
            getConversation = {
                throw IllegalStateException("conversation fetch should not run for duplicate")
            },
            resolveSpawnableSkill = { resolveSkill ->
                if (resolveSkill != assistantId.toString()) {
                    throw IllegalArgumentException("unexpected skill id")
                }
                assistant
            },
        )

        val conflict = kotlin.runCatching {
            startOrResumeA2aTask(
                appScope = this,
                params = paramsFor(contextId, assistantId.toString(), "message-2"),
                messageFlowClient = flowClient,
                registry = registry,
                getConversation = {
                    throw IllegalStateException("conversation fetch should not run for conflict")
                },
                resolveSpawnableSkill = { resolveSkill ->
                    if (resolveSkill != assistantId.toString()) {
                        throw IllegalArgumentException("unexpected skill id")
                    }
                    assistant
                },
            )
        }.exceptionOrNull()

        assertEquals(accepted.entry.taskId, duplicate.taskId)
        assertTrue(conflict is BadRequestException)
        assertEquals(0, flowClient.initializeCalls)
        assertEquals(0, flowClient.sendCalls)
        assertEquals(1, registry.tasks.size)
        assertEquals(1, registry.activeByContext[contextId]?.let { if (it == accepted.entry.taskId) 1 else 0 } ?: 0)
    }

    @Test
    fun `initialize failure rolls back admission and clears active context mapping`() = runBlocking {
        val registry = A2aTaskRegistry()
        val contextId = Uuid.random()
        val assistantId = Uuid.random()
        val assistant = Assistant(id = assistantId, spawnable = true)
        val flowClient = InboundFakeA2aMessageFlowClient(failOnInit = true)

        val failure = kotlin.runCatching {
            startOrResumeA2aTask(
                appScope = this,
                params = paramsFor(contextId, assistantId.toString(), "message-1"),
                messageFlowClient = flowClient,
                registry = registry,
                getConversation = {
                    throw IllegalStateException("conversation fetch should not run for rollback failure path")
                },
                resolveSpawnableSkill = { resolveSkill ->
                    if (resolveSkill != assistantId.toString()) {
                        throw IllegalArgumentException("unexpected skill id")
                    }
                    assistant
                },
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, flowClient.initializeCalls)
        assertEquals(0, flowClient.sendCalls)
        assertEquals(0, registry.tasks.size)
        assertEquals(0, registry.seenMessageIds[contextId]?.size ?: 0)
        assertEquals(null, registry.activeByContext[contextId])
    }

    @Test
    fun `late terminal subscriber reads terminal status from status flow`() = runBlocking {
        val registry = A2aTaskRegistry(maxTasks = 1)
        val first = (registry.admit(Uuid.random(), Uuid.random(), "message-a") as A2aAdmission.Accepted).entry

        val terminalStatus = async(start = CoroutineStart.UNDISPATCHED) {
            registry.status(first.taskId)!!.first { it.state in setOf(A2aTaskState.COMPLETED, A2aTaskState.CANCELED, A2aTaskState.FAILED) }
        }

        registry.transition(first.taskId, A2aTaskState.COMPLETED, terminal = true)

        withTimeout(1_000) {
            assertEquals(A2aTaskState.COMPLETED, terminalStatus.await().state)
        }
        assertEquals(A2aTaskState.COMPLETED, registry.get(first.taskId)!!.state)
    }

    @Test
    fun `terminal status remains queryable after overflow before terminal is delivered`() {
        val registry = A2aTaskRegistry(maxTasks = 1)
        val first = (registry.admit(Uuid.random(), Uuid.random(), "message-a") as A2aAdmission.Accepted).entry
        registry.transition(
            first.taskId,
            A2aTaskState.COMPLETED,
            terminal = true,
            markTerminalDelivered = false,
        )
        registry.admit(Uuid.random(), Uuid.random(), "message-b") as A2aAdmission.Accepted
        registry.admit(Uuid.random(), Uuid.random(), "message-c") as A2aAdmission.Accepted

        assertEquals(A2aTaskState.COMPLETED, registry.status(first.taskId)!!.value.state)
        assertEquals(A2aTaskState.COMPLETED, registry.get(first.taskId)?.state)
    }

    @Test
    fun `final artifact update is authoritative and emitted once for terminal tasks`() {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "message-final") as A2aAdmission.Accepted).entry
        val partialNode = Uuid.parse("11111111-1111-1111-1111-111111111111")

        registry.updateLastSentText(entry.taskId, partialNode, "partial text")
        registry.updateLastSentText(entry.taskId, A2A_FINAL_ARTIFACT_NODE_ID, "final text")
        entry.state = A2aTaskState.COMPLETED

        val task = registry.get(entry.taskId)!!.toA2aTask(conversation = null)
        val events = currentStreamEvents(entry, conversation = null)

        assertEquals("final text", task.artifacts.single().parts.filterIsInstance<A2aPart.TextPart>().single().text)
        assertEquals(2, events.size)
        assertEquals(1, events.count { it is A2aStreamEvent.TaskArtifactUpdateEvent })
        assertEquals(
            "final text",
            (events[0] as A2aStreamEvent.TaskArtifactUpdateEvent).artifact.parts
                .filterIsInstance<A2aPart.TextPart>()
                .single()
                .text,
        )
    }
}
