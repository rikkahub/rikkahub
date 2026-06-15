package me.rerere.rikkahub.web.a2a

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import java.io.StringWriter
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class A2aLifecycleRaceTest {

    @Test
    fun `fast terminal jobs still invoke completion handlers registered after completion`() {
        val job = Job()
        job.complete()

        var called = false
        job.invokeOnCompletion {
            called = true
        }

        assertTrue(called)
        assertEquals(
            A2aTaskState.COMPLETED,
            classifyA2aTerminalState(cancelRequested = false, newError = false, pendingApproval = false),
        )
    }

    @Test
    fun `terminal classifier prioritizes cancellation over errors and pending approval`() {
        assertEquals(
            A2aTaskState.CANCELED,
            classifyA2aTerminalState(cancelRequested = true, newError = true, pendingApproval = true),
        )
    }

    @Test
    fun `late terminal stream initial events include artifact then final status`() {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        registry.updateLastSentText(entry.taskId, Uuid.random(), "done")
        registry.transition(entry.taskId, A2aTaskState.COMPLETED, terminal = true)

        val events = currentStreamEvents(entry, conversation = null)

        assertEquals(2, events.size)
        assertTrue(events[0] is A2aStreamEvent.TaskArtifactUpdateEvent)
        val status = events[1] as A2aStreamEvent.TaskStatusUpdateEvent
        assertTrue(status.final)
        assertEquals(A2aTaskState.COMPLETED, status.status.state)
    }

    @Test
    fun `non terminal stream initial event stays open`() {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry

        val events = currentStreamEvents(entry, conversation = null)

        assertEquals(1, events.size)
        assertFalse((events.single() as A2aStreamEvent.TaskStatusUpdateEvent).final)
    }

    @Test
    fun `canceling jobless input required task can stop its collector directly`() {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        val collector = registry.startCollector(entry.taskId) { Job() }

        registry.transition(entry.taskId, A2aTaskState.INPUT_REQUIRED, terminal = false)
        registry.requestCancel(entry.taskId)
        registry.transition(entry.taskId, A2aTaskState.CANCELED, terminal = true)
        registry.cancelCollector(entry.taskId)

        assertTrue(collector.isCancelled)
        assertEquals(A2aTaskState.CANCELED, registry.get(entry.taskId)?.state)
    }

    @Test
    fun `terminal current status includes pending input from latest conversation state`() {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        registry.transition(entry.taskId, A2aTaskState.INPUT_REQUIRED, terminal = false)
        val conversation = Conversation(
            id = entry.contextId,
            assistantId = entry.assistantId,
            messageNodes = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "tool-1",
                            toolName = "ask_user",
                            input = "continue?",
                            approvalState = ToolApprovalState.Pending,
                        )
                    ),
                ).toMessageNode()
            ),
        )

        val status = currentStreamEvents(entry, conversation)
            .single() as A2aStreamEvent.TaskStatusUpdateEvent

        assertEquals(A2aTaskState.INPUT_REQUIRED, status.status.state)
        assertEquals("tool-1", status.status.input.single().toolCallId)
    }

    @Test
    fun `terminal transition before status subscription emits terminal status once`() = runBlocking {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        registry.updateLastSentText(entry.taskId, Uuid.parse("11111111-1111-1111-1111-111111111111"), "done")
        val writer = StringWriter()

        val stream = async(start = CoroutineStart.UNDISPATCHED) {
            streamA2aTaskEvents(
                writer = writer,
                requestId = null,
                registry = registry,
                taskEntry = entry,
                getCurrentConversation = { null },
                onBeforeStatusSubscription = {
                    registry.transition(entry.taskId, A2aTaskState.COMPLETED, terminal = true)
                },
            )
        }

        val events = withTimeout(1_000) {
            stream.await()
            parseStreamEvents(writer.toString())
        }

        val statusEvents = events.filterIsInstance<A2aStreamEvent.TaskStatusUpdateEvent>()
        assertEquals(1, statusEvents.size)
        assertTrue(statusEvents.single().final)
        assertEquals(A2aTaskState.COMPLETED, statusEvents.single().status.state)

        val artifactEvents = events.filterIsInstance<A2aStreamEvent.TaskArtifactUpdateEvent>()
        assertTrue(artifactEvents.isNotEmpty())
        val finalArtifact = artifactEvents.last()
        assertTrue(finalArtifact.artifact.lastChunk)
        assertEquals("done", finalArtifact.artifact.parts.first { it is A2aPart.TextPart }.let {
            (it as A2aPart.TextPart).text
        })
    }

    @Test
    fun `live terminal stream emits authoritative final artifact once before terminal status`() = runBlocking {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        val finalText = "authoritative final artifact text"
        val conversation = Conversation(
            id = entry.contextId,
            assistantId = entry.assistantId,
            messageNodes = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(finalText)),
                ).toMessageNode(),
            ),
        )
        val writer = StringWriter()

        val stream = async(start = CoroutineStart.UNDISPATCHED) {
            streamA2aTaskEvents(
                writer = writer,
                requestId = null,
                registry = registry,
                taskEntry = entry,
                getCurrentConversation = { conversation },
            )
        }

        val events = withTimeout(1_000) {
            while (!writer.toString().contains("\"final\":false")) {
                yield()
            }

            registry.updateLastSentText(
                entry.taskId,
                A2A_FINAL_ARTIFACT_NODE_ID,
                finalText,
            )
            registry.emit(
                entry.taskId,
                A2aStreamEvent.TaskArtifactUpdateEvent(
                    taskId = entry.taskId,
                    contextId = entry.contextId.toString(),
                    artifact = A2aArtifact(
                        artifactId = entry.taskId,
                        parts = listOf(A2aPart.TextPart(finalText)),
                        append = false,
                        lastChunk = true,
                    ),
                    append = false,
                ),
            )
            registry.transition(entry.taskId, A2aTaskState.COMPLETED, terminal = true)

            stream.await()
            parseStreamEvents(writer.toString())
        }

        val statusEvents = events.filterIsInstance<A2aStreamEvent.TaskStatusUpdateEvent>()
        val openStatus = statusEvents.single { !it.final }
        val terminalStatus = statusEvents.single { it.final }
        assertEquals(A2aTaskState.SUBMITTED, openStatus.status.state)
        assertEquals(A2aTaskState.COMPLETED, terminalStatus.status.state)

        val artifactEvents = events.filterIsInstance<A2aStreamEvent.TaskArtifactUpdateEvent>()
        val authoritativeFinalArtifacts = artifactEvents.filter { it.artifact.lastChunk && it.artifact.parts.filterIsInstance<A2aPart.TextPart>().singleOrNull()?.text == finalText }
        assertEquals(1, authoritativeFinalArtifacts.size)
        assertEquals(finalText, authoritativeFinalArtifacts.single().artifact.parts.filterIsInstance<A2aPart.TextPart>().single().text)

        val finalArtifactEvent = authoritativeFinalArtifacts.single()
        assertTrue(finalArtifactEvent.artifact.lastChunk)

        assertTrue(events.indexOf(finalArtifactEvent) < events.indexOf(terminalStatus))
        assertEquals(events.size - 1, events.indexOf(terminalStatus))
    }

    @Test
    fun `late terminal artifact reconciliation emits authoritative final artifact on terminal status`() = runBlocking {
        val registry = A2aTaskRegistry()
        val entry = (registry.admit(Uuid.random(), Uuid.random(), "m-1") as A2aAdmission.Accepted).entry
        val partialNode = Uuid.parse("11111111-1111-1111-1111-111111111111")
        registry.updateLastSentText(entry.taskId, partialNode, "stale partial")
        registry.transition(entry.taskId, A2aTaskState.COMPLETED, terminal = true, markTerminalDelivered = false)

        val writer = StringWriter()
        val finalText = "authoritative final text"
        val conversation = Conversation(
            id = entry.contextId,
            assistantId = entry.assistantId,
            messageNodes = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(finalText)),
                ).toMessageNode(),
            ),
        )

        val stream = async(start = CoroutineStart.UNDISPATCHED) {
            streamA2aTaskEvents(
                writer = writer,
                requestId = null,
                registry = registry,
                taskEntry = entry,
                getCurrentConversation = { conversation },
                onBeforeStatusSubscription = {
                    registry.updateLastSentText(
                        entry.taskId,
                        A2A_FINAL_ARTIFACT_NODE_ID,
                        finalText,
                    )
                },
            )
        }

        val events = withTimeout(1_000) {
            stream.await()
            parseStreamEvents(writer.toString())
        }

        val statusEvents = events.filterIsInstance<A2aStreamEvent.TaskStatusUpdateEvent>()
        assertEquals(1, statusEvents.size)
        assertEquals(A2aTaskState.COMPLETED, statusEvents.single().status.state)
        assertEquals(true, statusEvents.single().final)

        val artifactEvents = events.filterIsInstance<A2aStreamEvent.TaskArtifactUpdateEvent>()
        val authoritativeFinalEvents = artifactEvents.filter { it.artifact.parts.filterIsInstance<A2aPart.TextPart>().single().text == finalText }
        assertEquals(1, authoritativeFinalEvents.size)
        assertTrue(authoritativeFinalEvents.single().artifact.lastChunk)
        assertEquals(finalText, authoritativeFinalEvents.single().artifact.parts.filterIsInstance<A2aPart.TextPart>().single().text)
        assertEquals(finalText, artifactEvents.last().artifact.parts.filterIsInstance<A2aPart.TextPart>().single().text)
    }
}

private fun parseStreamEvents(raw: String): List<A2aStreamEvent> = raw
    .lineSequence()
    .filter { it.startsWith("data: ") }
    .mapNotNull { dataLine ->
        val jsonLine = dataLine.removePrefix("data: ")
        val response = runCatching { JsonInstant.decodeFromString<JsonRpcSuccess>(jsonLine) }.getOrNull() ?: return@mapNotNull null
        runCatching {
            JsonInstant.decodeFromJsonElement(A2aStreamEvent.TaskStatusUpdateEvent.serializer(), response.result)
        }.getOrNull()
            ?: runCatching {
                JsonInstant.decodeFromJsonElement(A2aStreamEvent.TaskArtifactUpdateEvent.serializer(), response.result)
            }.getOrNull()
    }
    .toList()
