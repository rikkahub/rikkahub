package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.FakeVoiceConversationStore
import me.rerere.rikkahub.voiceagent.FakeVoiceToolApi
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceToolApi
import me.rerere.rikkahub.voiceagent.VoiceToolStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.telemetry.NoOpVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.RecordingVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobPollResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesJobSubmitResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.voicelab.VoiceFailure
import me.rerere.rikkahub.voiceagent.voicelab.VoiceFailureKind
import me.rerere.rikkahub.voiceagent.voicelab.VoiceFailureSource
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class HermesJobManagerTest {
    private val persister = VoiceConversationPersister()

    @Test
    fun `submitted job keeps polling after session bridge detaches`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 7L)
        manager.submit(callId = "call-1", prompt = "slow request")
        assertEquals("call-1" to "slow request", toolApi.awaitRequest("call-1"))
        manager.detachBridge(bridge)

        toolApi.complete(response(callId = "call-1", answer = "detached answer"))
        conversationStore.awaitHermesRecord("call-1") {
            it.status == HermesQueueStatus.Complete && it.answer == "detached answer"
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-1" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("detached answer", record.answer)
        assertFalse(record.resultAnnounced)
        assertTrue(bridge.completionFollowUps.isEmpty())
        assertFalse(toolApi.wasCancelled("call-1"))
    }

    @Test
    fun `submitted job persists pending before remote submit returns`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val blockedSubmit = toolApi.blockSubmit("call-pending-first")
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-pending-first", prompt = "pending first")
        assertTrue(blockedSubmit.started.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesRecord("call-pending-first") {
            it.status == HermesQueueStatus.Pending && it.jobId == null
        }

        blockedSubmit.release.countDown()
        conversationStore.awaitHermesRecord("call-pending-first") {
            it.status == HermesQueueStatus.Queued && it.jobId != null
        }
    }

    @Test
    fun `submitted job records canonical prompt and answer observability events`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val observability = RecordingVoiceObservability()
        val trace = VoiceTraceContext(traceId = "trace-123", voiceSessionId = "session-456")
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            observability = observability,
            traceContext = trace,
        )

        manager.submit(callId = "call-1", prompt = "private prompt")
        assertEquals("call-1" to "private prompt", toolApi.awaitRequest("call-1"))
        toolApi.complete(response(callId = "call-1", answer = "private answer"))
        conversationStore.awaitHermesRecord("call-1") {
            it.status == HermesQueueStatus.Complete && it.answer == "private answer"
        }

        val submitted = observability.events.single { it.name == "voicelab.mobile.hermes_tool.submitted" }
        assertEquals(trace, submitted.trace)
        assertEquals("call-1", submitted.attributes["callId"])
        assertEquals("private prompt", submitted.attributes["gemini.tool_call.prompt"])
        assertEquals(14, submitted.attributes["gemini.tool_call.prompt.chars"])
        assertEquals(
            "6fe06b970bb77bb96bee521acbebf7e932c2bbc684494ad299a7e1851347fc8e",
            submitted.attributes["gemini.tool_call.prompt.sha256"],
        )
        assertEquals(false, submitted.attributes["gemini.tool_call.prompt.truncated"])
        assertFalse(submitted.attributes.containsKey("prompt"))
        assertFalse(submitted.attributes.containsKey("prompt.chars"))
        assertFalse(submitted.attributes.containsKey("prompt.sha256"))
        assertFalse(submitted.attributes.containsKey("prompt.truncated"))

        val completed = observability.events.single { it.name == "voicelab.mobile.hermes_tool.completed" }
        assertEquals(trace, completed.trace)
        assertEquals("call-1", completed.attributes["callId"])
        assertEquals("job-1", completed.attributes["jobId"])
        assertEquals("private answer", completed.attributes["hermes.response.answer"])
        assertEquals(14, completed.attributes["hermes.response.answer.chars"])
        assertEquals(
            "58c61586e981a11c4d5fd85b0b03b78c1b686743e5d02f4c6c9c1c677dc7a4da",
            completed.attributes["hermes.response.answer.sha256"],
        )
        assertEquals(false, completed.attributes["hermes.response.answer.truncated"])
        assertFalse(completed.attributes.containsKey("answer"))
        assertFalse(completed.attributes.containsKey("answer.chars"))
        assertFalse(completed.attributes.containsKey("answer.sha256"))
        assertFalse(completed.attributes.containsKey("answer.truncated"))
    }

    @Test
    fun `cancel after pending persistence but before submit skips remote submit`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val blockedPendingUpdate = conversationStore.blockAfterNextUpdate()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-cancel-after-pending", prompt = "cancel after pending")
        assertTrue(blockedPendingUpdate.started.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesRecord("call-cancel-after-pending") {
            it.status == HermesQueueStatus.Pending && it.jobId == null
        }

        manager.cancel("call-cancel-after-pending")
        blockedPendingUpdate.release.countDown()
        conversationStore.awaitHermesRecord("call-cancel-after-pending") {
            it.status == HermesQueueStatus.Canceled
        }
        delay(50)

        assertTrue(toolApi.requests.isEmpty())
    }

    @Test
    fun `hung submit request times out and expires pending record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val blockedSubmit = toolApi.blockSubmitCancellable("call-submit-timeout")
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            maxElapsedMs = 30L,
        )

        manager.submit(callId = "call-submit-timeout", prompt = "submit timeout")
        assertTrue(blockedSubmit.started.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesRecord("call-submit-timeout") {
            it.status == HermesQueueStatus.Expired
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-submit-timeout" }
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals("Hermes job polling timed out.", record.error)
        toolApi.awaitCancelled("call-submit-timeout")
    }

    @Test
    fun `submit failure persists failed pending record`() = runTest {
        val toolApi = FakeVoiceToolApi().apply {
            failSubmit("call-submit-fails", IllegalStateException("submit failed"))
        }
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-submit-fails", prompt = "submit fails")
        conversationStore.awaitHermesRecord("call-submit-fails") {
            it.status == HermesQueueStatus.Failed
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-submit-fails" }
        assertEquals(HermesQueueStatus.Failed, record.status)
        assertEquals("submit failed", record.error)
        assertEquals(0, toolApi.pollCount("call-submit-fails"))
    }

    @Test
    fun `submit after terminal no job record with same call id creates fresh remote job`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-reused-no-job",
                prompt = "old failed prompt",
                status = VoiceToolRecordStatus.Failed("old submit failed"),
                jobId = null,
                resultAnnounced = true,
            )
        }
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-reused-no-job", prompt = "new prompt")
        assertEquals("call-reused-no-job" to "new prompt", toolApi.awaitRequest("call-reused-no-job"))
        toolApi.complete(response(callId = "call-reused-no-job", answer = "new answer"))
        conversationStore.awaitHermesRecord("call-reused-no-job") {
            it.status == HermesQueueStatus.Complete && it.answer == "new answer"
        }

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-reused-no-job" }
        assertEquals(listOf(null, "job-1"), records.map { it.jobId })
        assertEquals(listOf(HermesQueueStatus.Failed, HermesQueueStatus.Complete), records.map { it.status })
    }

    @Test
    fun `same call id from different sessions can submit while older job remains active`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        assertTrue(manager.submit(callId = "call-reused-active", prompt = "older prompt", activeKey = "session-1:call-reused-active"))
        assertEquals("call-reused-active" to "older prompt", toolApi.awaitRequest("call-reused-active"))
        conversationStore.awaitHermesRecord("call-reused-active") {
            it.jobId == "job-1" && it.status == HermesQueueStatus.Queued
        }

        assertTrue(manager.submit(callId = "call-reused-active", prompt = "new prompt", activeKey = "session-2:call-reused-active"))
        withTimeout(500) {
            while (toolApi.requests.count { it.first == "call-reused-active" } < 2) {
                delay(10)
            }
        }
        assertEquals(2, toolApi.requests.count { it.first == "call-reused-active" })
        assertEquals(
            listOf("older prompt", "new prompt"),
            toolApi.requests.filter { it.first == "call-reused-active" }.map { it.second },
        )
    }

    @Test
    fun `same call id from same session is still treated as duplicate while active`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        assertTrue(manager.submit(callId = "call-duplicate-active", prompt = "first prompt", activeKey = "session-1:call-duplicate-active"))
        assertEquals("call-duplicate-active" to "first prompt", toolApi.awaitRequest("call-duplicate-active"))

        assertFalse(manager.submit(callId = "call-duplicate-active", prompt = "duplicate prompt", activeKey = "session-1:call-duplicate-active"))
        assertEquals(listOf("first prompt"), toolApi.requests.map { it.second })
    }

    @Test
    fun `running submit status persists running record and keeps polling`() = runTest {
        val toolApi = FakeVoiceToolApi().apply {
            scriptSubmitStatus(callId = "call-submit-running", status = "running")
        }
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-submit-running", prompt = "submit running")
        assertEquals("call-submit-running" to "submit running", toolApi.awaitRequest("call-submit-running"))
        conversationStore.awaitHermesRecord("call-submit-running") {
            it.status == HermesQueueStatus.Running && it.jobId != null
        }
        toolApi.complete(response(callId = "call-submit-running", answer = "running answer"))
        conversationStore.awaitHermesRecord("call-submit-running") {
            it.status == HermesQueueStatus.Complete && it.answer == "running answer"
        }
    }

    @Test
    fun `terminal submit statuses persist terminal records without polling`() = runTest {
        val toolApi = FakeVoiceToolApi().apply {
            scriptSubmitStatus(callId = "call-submit-failed", status = "failed")
            scriptSubmitStatus(callId = "call-submit-expired", status = "expired")
            scriptSubmitStatus(callId = "call-submit-timeout", status = "timeout")
            scriptSubmitStatus(callId = "call-submit-canceled", status = "canceled")
        }
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-submit-failed", prompt = "submit failed")
        manager.submit(callId = "call-submit-expired", prompt = "submit expired")
        manager.submit(callId = "call-submit-timeout", prompt = "submit timeout")
        manager.submit(callId = "call-submit-canceled", prompt = "submit canceled")
        conversationStore.awaitHermesRecord("call-submit-failed") {
            it.status == HermesQueueStatus.Failed
        }
        conversationStore.awaitHermesRecord("call-submit-expired") {
            it.status == HermesQueueStatus.Expired
        }
        conversationStore.awaitHermesRecord("call-submit-timeout") {
            it.status == HermesQueueStatus.Expired
        }
        conversationStore.awaitHermesRecord("call-submit-canceled") {
            it.status == HermesQueueStatus.Canceled
        }

        assertEquals(0, toolApi.pollCount("call-submit-failed"))
        assertEquals(0, toolApi.pollCount("call-submit-expired"))
        assertEquals(0, toolApi.pollCount("call-submit-timeout"))
        assertEquals(0, toolApi.pollCount("call-submit-canceled"))
    }

    @Test
    fun `queued acknowledgement is sent to attached bridge`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 42L)
        manager.submit(callId = "call-ack", prompt = "ack request")
        bridge.awaitQueuedAcknowledgements(count = 1)

        assertEquals(listOf("call-ack" to 42L), bridge.queuedAcknowledgements)
    }

    @Test
    fun `blocking queued acknowledgement does not stop polling`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge()
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            bridgeSendTimeoutMs = 20L,
        )

        val blockedAck = bridge.blockNextQueuedAcknowledgement()
        manager.attachBridge(bridge = bridge, sessionId = 43L)
        manager.submit(callId = "call-blocked-ack", prompt = "blocked ack")
        assertTrue(blockedAck.started.await(500, TimeUnit.MILLISECONDS))
        assertEquals("call-blocked-ack" to "blocked ack", toolApi.awaitRequest("call-blocked-ack"))
        toolApi.complete(response(callId = "call-blocked-ack", answer = "blocked ack answer"))
        conversationStore.awaitHermesRecord("call-blocked-ack") {
            it.status == HermesQueueStatus.Complete && it.answer == "blocked ack answer"
        }

        blockedAck.release.countDown()
    }

    @Test
    fun `detached bridge does not receive delayed queued acknowledgement`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge()
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            bridgeSendTimeoutMs = 20L,
        )

        val blockedAck = bridge.blockNextQueuedAcknowledgement()
        manager.attachBridge(bridge = bridge, sessionId = 45L)
        manager.submit(callId = "call-detached-ack", prompt = "detached ack")
        assertTrue(blockedAck.started.await(500, TimeUnit.MILLISECONDS))
        manager.detachBridge(bridge)
        conversationStore.awaitHermesRecord("call-detached-ack") {
            it.status == HermesQueueStatus.Queued
        }

        delay(50)
        blockedAck.release.countDown()
        delay(50)

        assertTrue(bridge.queuedAcknowledgements.isEmpty())
    }

    @Test
    fun `throwing queued acknowledgement does not stop polling`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge().apply {
            throwQueuedAcknowledgement = true
        }
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 44L)
        manager.submit(callId = "call-throwing-ack", prompt = "throwing ack")
        assertEquals("call-throwing-ack" to "throwing ack", toolApi.awaitRequest("call-throwing-ack"))
        toolApi.complete(response(callId = "call-throwing-ack", answer = "throwing ack answer"))
        conversationStore.awaitHermesRecord("call-throwing-ack") {
            it.status == HermesQueueStatus.Complete && it.answer == "throwing ack answer"
        }
    }

    @Test
    fun `resume polls persisted active jobs and announces when bridge is attached`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-resume",
                prompt = "resume request",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-resume",
            )
        }
        val toolApi = FakeVoiceToolApi().apply {
            scriptPollSucceeded(jobId = "job-resume", callId = "call-resume", answer = "resumed answer")
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 9L)
        manager.resumeActiveJobs()
        conversationStore.awaitHermesRecord("call-resume") {
            it.status == HermesQueueStatus.Complete && it.resultAnnounced
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-resume" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("resumed answer", record.answer)
        assertTrue(record.resultAnnounced)
        assertEquals(
            listOf(
                CompletionFollowUp(
                    callId = "call-resume",
                    prompt = "resume request",
                    answer = "resumed answer",
                    sessionId = 9L,
                )
            ),
            bridge.completionFollowUps,
        )
    }

    @Test
    fun `explicit cancel cancels remote job and persists canceled`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-cancel", prompt = "cancel request")
        assertEquals("call-cancel" to "cancel request", toolApi.awaitRequest("call-cancel"))
        conversationStore.awaitHermesRecord("call-cancel") {
            it.status == HermesQueueStatus.Queued && it.jobId != null
        }

        manager.cancel("call-cancel")
        toolApi.awaitRemoteCancelled("call-cancel")
        conversationStore.awaitHermesRecord("call-cancel") {
            it.status == HermesQueueStatus.Canceled
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-cancel" }
        assertEquals(HermesQueueStatus.Canceled, record.status)
        assertEquals("Hermes job canceled.", record.error)
    }

    @Test
    fun `keyed cancel does not cancel another session with reused call id`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        assertTrue(manager.submit(callId = "call-reused-cancel", prompt = "older prompt", activeKey = "session-1:call-reused-cancel"))
        assertEquals("call-reused-cancel" to "older prompt", toolApi.awaitRequest("call-reused-cancel"))
        conversationStore.awaitHermesRecord("call-reused-cancel") {
            it.jobId == "job-1" && it.status == HermesQueueStatus.Queued
        }
        assertTrue(manager.submit(callId = "call-reused-cancel", prompt = "new prompt", activeKey = "session-2:call-reused-cancel"))
        withTimeout(500) {
            while (toolApi.requests.count { it.first == "call-reused-cancel" } < 2) {
                delay(10)
            }
        }
        conversationStore.awaitHermesRecord("call-reused-cancel") {
            it.jobId == "job-2" && it.status == HermesQueueStatus.Queued
        }

        manager.cancel(callId = "call-reused-cancel", activeKey = "session-1:call-reused-cancel")
        toolApi.awaitRemoteCancelledJob("job-1")
        conversationStore.awaitHermesRecord("call-reused-cancel") {
            it.jobId == "job-1" && it.status == HermesQueueStatus.Canceled
        }

        toolApi.complete(response(callId = "call-reused-cancel", answer = "new answer"))
        conversationStore.awaitHermesRecord("call-reused-cancel") {
            it.jobId == "job-2" &&
                it.status == HermesQueueStatus.Complete &&
                it.answer == "new answer"
        }
    }

    @Test
    fun `remote cancel timeout keeps local canceled state without waiting for remote cleanup`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val blockedCancel = toolApi.blockCancel("call-cancel-timeout")
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            remoteCancelTimeoutMs = 20L,
        )

        manager.submit(callId = "call-cancel-timeout", prompt = "cancel timeout request")
        assertEquals("call-cancel-timeout" to "cancel timeout request", toolApi.awaitRequest("call-cancel-timeout"))
        conversationStore.awaitHermesRecord("call-cancel-timeout") {
            it.status == HermesQueueStatus.Queued
        }

        manager.cancel("call-cancel-timeout")
        withTimeout(500) {
            blockedCancel.started.await()
        }
        conversationStore.awaitHermesRecord("call-cancel-timeout") {
            it.status == HermesQueueStatus.Canceled
        }
        withTimeout(500) {
            blockedCancel.cancelled.await()
        }

        assertFalse(toolApi.wasRemoteCancelled("call-cancel-timeout"))
        blockedCancel.release.complete(Unit)
    }

    @Test
    fun `tool status callback emits actual completed call while another job remains active`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val statuses = Collections.synchronizedList(mutableListOf<VoiceToolStatus>())
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            updateToolStatus = statuses::add,
        )

        manager.submit(callId = "call-active", prompt = "active request")
        assertEquals("call-active" to "active request", toolApi.awaitRequest("call-active"))
        manager.submit(callId = "call-done", prompt = "done request")
        assertEquals("call-done" to "done request", toolApi.awaitRequest("call-done"))

        toolApi.scriptQueuedPolls(callId = "call-active", count = 1)
        toolApi.complete(response(callId = "call-done", answer = "done answer"))
        conversationStore.awaitHermesRecord("call-done") {
            it.status == HermesQueueStatus.Complete
        }

        assertTrue(
            statuses.any {
                it is VoiceToolStatus.HermesAnswered && it.callId == "call-done"
            },
        )

        toolApi.complete(response(callId = "call-active", answer = "active answer"))
        conversationStore.awaitHermesRecord("call-active") {
            it.status == HermesQueueStatus.Complete
        }
    }

    @Test
    fun `cancel after terminal result does not overwrite completed record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-complete", prompt = "complete request")
        assertEquals("call-complete" to "complete request", toolApi.awaitRequest("call-complete"))
        toolApi.complete(response(callId = "call-complete", answer = "complete answer"))
        conversationStore.awaitHermesRecord("call-complete") {
            it.status == HermesQueueStatus.Complete
        }

        manager.cancel("call-complete")
        delay(50)

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-complete" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("complete answer", record.answer)
        assertFalse(toolApi.wasRemoteCancelled("call-complete"))
    }

    @Test
    fun `cancel while completed job is still unwinding does not overwrite completed record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 1L)
        manager.submit(callId = "call-unwinding", prompt = "unwinding request")
        assertEquals("call-unwinding" to "unwinding request", toolApi.awaitRequest("call-unwinding"))
        val blockedFollowUp = bridge.blockNextCompletionFollowUp()
        toolApi.complete(response(callId = "call-unwinding", answer = "unwinding answer"))
        conversationStore.awaitHermesRecord("call-unwinding") {
            it.status == HermesQueueStatus.Complete
        }
        blockedFollowUp.started.await()

        manager.cancel("call-unwinding")
        delay(50)
        blockedFollowUp.release.complete(Unit)
        conversationStore.awaitHermesRecord("call-unwinding") {
            it.status == HermesQueueStatus.Complete
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-unwinding" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("unwinding answer", record.answer)
        assertFalse(toolApi.wasRemoteCancelled("call-unwinding"))
    }

    @Test
    fun `cancel before completion persistence wins over completed poll response`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val hermesAnswers = Collections.synchronizedList(mutableListOf<String>())
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            writeHermesAnswer = hermesAnswers::add,
        )

        manager.submit(callId = "call-cancel-before-complete", prompt = "cancel before complete")
        assertEquals("call-cancel-before-complete" to "cancel before complete", toolApi.awaitRequest("call-cancel-before-complete"))
        conversationStore.awaitHermesRecord("call-cancel-before-complete") {
            it.status == HermesQueueStatus.Queued
        }
        val blockedCompletionUpdate = conversationStore.blockNextUpdate()
        toolApi.complete(response(callId = "call-cancel-before-complete", answer = "late answer"))
        assertTrue(blockedCompletionUpdate.started.await(500, TimeUnit.MILLISECONDS))

        manager.cancel("call-cancel-before-complete")
        delay(50)
        blockedCompletionUpdate.release.countDown()

        toolApi.awaitRemoteCancelled("call-cancel-before-complete")
        conversationStore.awaitHermesRecord("call-cancel-before-complete") {
            it.status == HermesQueueStatus.Canceled
        }
        delay(50)

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-cancel-before-complete" }
        assertEquals(1, records.size)
        assertEquals(HermesQueueStatus.Canceled, records.single().status)
        assertTrue(hermesAnswers.isEmpty())
    }

    @Test
    fun `cancel before malformed success failure persistence wins over fallback failure`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-cancel-malformed", prompt = "cancel malformed")
        assertEquals("call-cancel-malformed" to "cancel malformed", toolApi.awaitRequest("call-cancel-malformed"))
        conversationStore.awaitHermesRecord("call-cancel-malformed") {
            it.status == HermesQueueStatus.Queued
        }
        val blockedFailureUpdate = conversationStore.blockNextUpdate()
        toolApi.scriptPoll(
            callId = "call-cancel-malformed",
            response = MobileHermesJobPollResponse(
                callId = "call-cancel-malformed",
                status = "succeeded",
                answer = null,
            ),
        )
        assertTrue(blockedFailureUpdate.started.await(500, TimeUnit.MILLISECONDS))

        manager.cancel("call-cancel-malformed")
        delay(50)
        blockedFailureUpdate.release.countDown()

        toolApi.awaitRemoteCancelled("call-cancel-malformed")
        conversationStore.awaitHermesRecord("call-cancel-malformed") {
            it.status == HermesQueueStatus.Canceled
        }
        delay(50)

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-cancel-malformed" }
        assertEquals(1, records.size)
        assertEquals(HermesQueueStatus.Canceled, records.single().status)
        assertEquals("Hermes job canceled.", records.single().error)
    }

    @Test
    fun `failed completion follow-up remains unannounced for later bridge retry`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val failingBridge = RecordingHermesBridge().apply { failCompletionFollowUp = true }
        val retryBridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = failingBridge, sessionId = 1L)
        manager.submit(callId = "call-retry", prompt = "retry request")
        assertEquals("call-retry" to "retry request", toolApi.awaitRequest("call-retry"))
        toolApi.complete(response(callId = "call-retry", answer = "retry answer"))
        conversationStore.awaitHermesRecord("call-retry") {
            it.status == HermesQueueStatus.Complete && !it.resultAnnounced
        }

        manager.detachBridge(failingBridge)
        manager.attachBridge(bridge = retryBridge, sessionId = 2L)
        conversationStore.awaitHermesRecord("call-retry") {
            it.status == HermesQueueStatus.Complete && it.resultAnnounced
        }

        assertTrue(failingBridge.completionFollowUps.isEmpty())
        assertEquals(1, retryBridge.completionFollowUps.size)
        assertEquals("retry answer", retryBridge.completionFollowUps.single().answer)
    }

    @Test
    fun `successful follow-up is marked announced even if bridge detaches during mark`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 1L)
        manager.submit(callId = "call-detach-after-send", prompt = "detach after send")
        assertEquals("call-detach-after-send" to "detach after send", toolApi.awaitRequest("call-detach-after-send"))
        val blockedFollowUp = bridge.blockNextCompletionFollowUp()
        toolApi.complete(response(callId = "call-detach-after-send", answer = "delivered answer"))
        conversationStore.awaitHermesRecord("call-detach-after-send") {
            it.status == HermesQueueStatus.Complete && !it.resultAnnounced
        }
        blockedFollowUp.started.await()
        val blockedUpdate = conversationStore.blockNextUpdate()

        blockedFollowUp.release.complete(Unit)
        assertTrue(blockedUpdate.started.await(500, TimeUnit.MILLISECONDS))
        manager.detachBridge(bridge)
        blockedUpdate.release.countDown()

        conversationStore.awaitHermesRecord("call-detach-after-send") {
            it.status == HermesQueueStatus.Complete && it.resultAnnounced
        }

        assertEquals(1, bridge.completionFollowUps.size)
    }

    @Test
    fun `completion waiting behind detached bridge remains unannounced for later retry`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-blocking",
                prompt = "blocking announcement",
                status = VoiceToolRecordStatus.Complete("blocking answer"),
                jobId = "job-blocking",
                resultAnnounced = false,
            )
        }
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        val blockedFollowUp = bridge.blockNextCompletionFollowUp()
        manager.attachBridge(bridge = bridge, sessionId = 1L)
        blockedFollowUp.started.await()

        manager.submit(callId = "call-waiting", prompt = "waiting completion")
        assertEquals("call-waiting" to "waiting completion", toolApi.awaitRequest("call-waiting"))
        toolApi.complete(response(callId = "call-waiting", answer = "waiting answer"))
        conversationStore.awaitHermesRecord("call-waiting") {
            it.status == HermesQueueStatus.Complete && !it.resultAnnounced
        }

        manager.detachBridge(bridge)
        blockedFollowUp.release.complete(Unit)
        delay(50)

        val waitingRecord = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-waiting" }
        assertEquals(HermesQueueStatus.Complete, waitingRecord.status)
        assertFalse(waitingRecord.resultAnnounced)
        assertEquals(listOf("call-blocking"), bridge.completionFollowUps.map { it.callId })
    }

    @Test
    fun `observer callback failures do not fail active Hermes job`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            updateToolStatus = { error("status observer failed") },
            recordDiagnostic = { _, _ -> error("diagnostic observer failed") },
            writeQueueEvent = { error("queue event observer failed") },
            writeHermesAnswer = { error("answer observer failed") },
        )

        manager.submit(callId = "call-observer-failure", prompt = "observer failure")
        assertEquals("call-observer-failure" to "observer failure", toolApi.awaitRequest("call-observer-failure"))
        toolApi.complete(response(callId = "call-observer-failure", answer = "observer answer"))
        conversationStore.awaitHermesRecord("call-observer-failure") {
            it.status == HermesQueueStatus.Complete && it.answer == "observer answer"
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-observer-failure" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("observer answer", record.answer)
    }

    @Test
    fun `poll timeout status persists expired record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-timeout", prompt = "timeout request")
        assertEquals("call-timeout" to "timeout request", toolApi.awaitRequest("call-timeout"))
        toolApi.scriptPoll(
            callId = "call-timeout",
            response = MobileHermesJobPollResponse(
                callId = "call-timeout",
                status = "timeout",
                error = "Hermes job timed out.",
            ),
        )

        conversationStore.awaitHermesRecord("call-timeout") {
            it.status == HermesQueueStatus.Expired
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-timeout" }
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals("Hermes job timed out.", record.error)
    }

    @Test
    fun `polled running status persists running record and emits calling status`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val statuses = Collections.synchronizedList(mutableListOf<VoiceToolStatus>())
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            updateToolStatus = statuses::add,
        )

        manager.submit(callId = "call-polled-running", prompt = "polled running")
        assertEquals("call-polled-running" to "polled running", toolApi.awaitRequest("call-polled-running"))
        toolApi.scriptPoll(
            callId = "call-polled-running",
            response = MobileHermesJobPollResponse(
                callId = "call-polled-running",
                status = "running",
            ),
        )
        conversationStore.awaitHermesRecord("call-polled-running") {
            it.status == HermesQueueStatus.Running
        }

        assertTrue(
            statuses.any {
                it is VoiceToolStatus.CallingHermes && it.callId == "call-polled-running"
            },
        )

        toolApi.complete(response(callId = "call-polled-running", answer = "running result"))
        conversationStore.awaitHermesRecord("call-polled-running") {
            it.status == HermesQueueStatus.Complete && it.answer == "running result"
        }
    }

    @Test
    fun `polled failed status persists failed record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-polled-failed", prompt = "polled failed")
        assertEquals("call-polled-failed" to "polled failed", toolApi.awaitRequest("call-polled-failed"))
        toolApi.failJob(callId = "call-polled-failed", message = "Hermes failed remotely.")

        conversationStore.awaitHermesRecord("call-polled-failed") {
            it.status == HermesQueueStatus.Failed
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-polled-failed" }
        assertEquals(HermesQueueStatus.Failed, record.status)
        assertEquals("Hermes failed remotely.", record.error)
    }

    @Test
    fun `polled expired status persists expired record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-polled-expired", prompt = "polled expired")
        assertEquals("call-polled-expired" to "polled expired", toolApi.awaitRequest("call-polled-expired"))
        toolApi.expireJob(callId = "call-polled-expired", message = "Hermes expired remotely.")

        conversationStore.awaitHermesRecord("call-polled-expired") {
            it.status == HermesQueueStatus.Expired
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-polled-expired" }
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals("Hermes expired remotely.", record.error)
    }

    @Test
    fun `hung poll request times out and cancels remote job`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            maxElapsedMs = 30L,
        )

        manager.submit(callId = "call-hung-timeout", prompt = "hung timeout request")
        assertEquals("call-hung-timeout" to "hung timeout request", toolApi.awaitRequest("call-hung-timeout"))

        conversationStore.awaitHermesRecord("call-hung-timeout") {
            it.status == HermesQueueStatus.Expired
        }
        toolApi.awaitRemoteCancelled("call-hung-timeout")

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-hung-timeout" }
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals("Hermes job polling timed out.", record.error)
    }

    @Test
    fun `transient poll failure retries and persists eventual success`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-transient", prompt = "transient request")
        assertEquals("call-transient" to "transient request", toolApi.awaitRequest("call-transient"))
        toolApi.scriptPollFailure("call-transient", IllegalStateException("temporary network failure"))
        toolApi.complete(response(callId = "call-transient", answer = "eventual answer"))

        conversationStore.awaitHermesRecord("call-transient") {
            it.status == HermesQueueStatus.Complete && it.answer == "eventual answer"
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-transient" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("eventual answer", record.answer)
    }

    @Test
    fun `legacy formatted poll failure is transient when it is not typed`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-legacy-message", prompt = "legacy message request")
        assertEquals("call-legacy-message" to "legacy message request", toolApi.awaitRequest("call-legacy-message"))
        toolApi.scriptPollFailure(
            callId = "call-legacy-message",
            error = IllegalStateException("Voice Lab request failed 404: job missing"),
        )
        toolApi.complete(response(callId = "call-legacy-message", answer = "eventual answer"))

        conversationStore.awaitHermesRecord("call-legacy-message") {
            it.status == HermesQueueStatus.Complete && it.answer == "eventual answer"
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-legacy-message" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("eventual answer", record.answer)
    }

    @Test
    fun `retryable typed poll failure retries and persists eventual success`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-retryable-http", prompt = "retryable http request")
        assertEquals(
            "call-retryable-http" to "retryable http request",
            toolApi.awaitRequest("call-retryable-http"),
        )
        toolApi.scriptPollFailure(
            callId = "call-retryable-http",
            error = VoiceLabHttpException(
                statusCode = 404,
                safePreview = "temporary voice lab failure",
                failure = VoiceFailure(
                    kind = VoiceFailureKind.HermesUnavailable,
                    safeMessage = "temporary voice lab failure",
                    safeSummary = "temporary voice lab failure",
                    retryable = true,
                    source = VoiceFailureSource.VoiceLab,
                ),
            ),
        )
        toolApi.complete(response(callId = "call-retryable-http", answer = "eventual answer"))

        conversationStore.awaitHermesRecord("call-retryable-http") {
            it.status == HermesQueueStatus.Complete && it.answer == "eventual answer"
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-retryable-http" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("eventual answer", record.answer)
    }

    @Test
    fun `terminal poll failure persists failed record without waiting for local timeout`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            maxElapsedMs = 10_000L,
        )

        manager.submit(callId = "call-terminal-http", prompt = "terminal http request")
        assertEquals("call-terminal-http" to "terminal http request", toolApi.awaitRequest("call-terminal-http"))
        toolApi.scriptPollFailure(
            callId = "call-terminal-http",
            error = VoiceLabHttpException(
                statusCode = 404,
                safePreview = "job missing",
                failure = VoiceFailure(
                    kind = VoiceFailureKind.HermesFailed,
                    safeMessage = "job missing",
                    safeSummary = "job missing",
                    retryable = false,
                    source = VoiceFailureSource.VoiceLab,
                ),
            ),
        )

        conversationStore.awaitHermesRecord("call-terminal-http") {
            it.status == HermesQueueStatus.Failed
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-terminal-http" }
        assertEquals(HermesQueueStatus.Failed, record.status)
        assertEquals("Voice Lab request failed 404: job missing", record.error)
    }

    @Test
    fun `terminal poll failure without typed failure persists failed record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            maxElapsedMs = 10_000L,
        )

        manager.submit(
            callId = "call-terminal-status-only",
            prompt = "terminal status request",
        )
        assertEquals(
            "call-terminal-status-only" to "terminal status request",
            toolApi.awaitRequest("call-terminal-status-only"),
        )
        toolApi.scriptPollFailure(
            callId = "call-terminal-status-only",
            error = VoiceLabHttpException(
                statusCode = 404,
                safePreview = "job missing",
                failure = null,
            ),
        )

        conversationStore.awaitHermesRecord("call-terminal-status-only") {
            it.status == HermesQueueStatus.Failed
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-terminal-status-only" }
        assertEquals(HermesQueueStatus.Failed, record.status)
        assertEquals("Voice Lab request failed 404: job missing", record.error)
    }

    @Test
    fun `unknown poll status persists failed record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-unknown-status", prompt = "unknown status request")
        assertEquals("call-unknown-status" to "unknown status request", toolApi.awaitRequest("call-unknown-status"))
        toolApi.scriptPoll(
            callId = "call-unknown-status",
            response = MobileHermesJobPollResponse(
                callId = "call-unknown-status",
                status = "mystery",
            ),
        )

        conversationStore.awaitHermesRecord("call-unknown-status") {
            it.status == HermesQueueStatus.Failed
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-unknown-status" }
        assertEquals(HermesQueueStatus.Failed, record.status)
        assertEquals("Unknown Hermes job status: mystery", record.error)
    }

    @Test
    fun `remote canceled poll status persists canceled record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-remote-canceled", prompt = "remote canceled request")
        assertEquals("call-remote-canceled" to "remote canceled request", toolApi.awaitRequest("call-remote-canceled"))
        toolApi.scriptPoll(
            callId = "call-remote-canceled",
            response = MobileHermesJobPollResponse(
                callId = "call-remote-canceled",
                status = "canceled",
                error = "Hermes canceled the job.",
            ),
        )

        conversationStore.awaitHermesRecord("call-remote-canceled") {
            it.status == HermesQueueStatus.Canceled
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-remote-canceled" }
        assertEquals(HermesQueueStatus.Canceled, record.status)
        assertEquals("Hermes canceled the job.", record.error)
    }

    @Test
    fun `resume expires already old active record without fresh timeout window`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-old-resume",
                prompt = "old resume request",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-old-resume",
            )
        }.withHermesCreatedAt(
            callId = "call-old-resume",
            createdAt = "2000-01-01T00:00:00Z",
        )
        val toolApi = FakeVoiceToolApi().apply {
            scriptPollSucceeded(jobId = "job-old-resume", callId = "call-old-resume", answer = "too late")
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            maxElapsedMs = 10_000L,
        )

        manager.resumeActiveJobs()
        conversationStore.awaitHermesRecord("call-old-resume") {
            it.status == HermesQueueStatus.Expired
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-old-resume" }
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals("Hermes job polling timed out.", record.error)
        toolApi.awaitRemoteCancelled("call-old-resume")
    }

    @Test
    fun `default durable age keeps nearly twenty four hour old resumed job active`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-default-age",
                prompt = "default age request",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-default-age",
            )
        }.withHermesCreatedAt(
            callId = "call-default-age",
            createdAt = Instant.now().minusSeconds(24 * 60 * 60 - 60).toString(),
        )
        val toolApi = FakeVoiceToolApi().apply {
            scriptPollSucceeded(jobId = "job-default-age", callId = "call-default-age", answer = "default age answer")
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val manager = HermesJobManager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            persister = persister,
            scope = this,
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 10L,
            pollRetryDelayMs = 1L,
        )

        manager.resumeActiveJobs()
        conversationStore.awaitHermesRecord("call-default-age") {
            it.status == HermesQueueStatus.Complete && it.answer == "default age answer"
        }

        assertFalse(toolApi.wasRemoteCancelled("call-default-age"))
    }

    @Test
    fun `resume expires active record missing job id`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-missing-job-id",
                prompt = "missing job id",
                status = VoiceToolRecordStatus.Running,
                jobId = null,
            )
        }
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.resumeActiveJobs()
        conversationStore.awaitHermesRecord("call-missing-job-id") {
            it.status == HermesQueueStatus.Expired
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-missing-job-id" }
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals("Hermes job was missing a job id.", record.error)
        assertTrue(toolApi.requests.isEmpty())
    }

    @Test
    fun `resume expires active record with invalid timing metadata`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-invalid-created-at",
                prompt = "invalid created at",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-invalid-created-at",
            )
        }.withHermesCreatedAt(
            callId = "call-invalid-created-at",
            createdAt = "not-an-instant",
        ).withHermesUpdatedAt(
            callId = "call-invalid-created-at",
            updatedAt = "also-not-an-instant",
        )
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.resumeActiveJobs()
        conversationStore.awaitHermesRecord("call-invalid-created-at") {
            it.status == HermesQueueStatus.Expired
        }

        val record = conversationStore.conversation.value.hermesQueueRecords()
            .single { it.callId == "call-invalid-created-at" }
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals("Hermes job had invalid timing metadata.", record.error)
        assertTrue(toolApi.requests.isEmpty())
    }

    @Test
    fun `resume skips same manager active pending submit`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val blockedPendingUpdate = conversationStore.blockAfterNextUpdate()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-resume-live-pending", prompt = "live pending")
        assertTrue(blockedPendingUpdate.started.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesRecord("call-resume-live-pending") {
            it.status == HermesQueueStatus.Pending && it.jobId == null
        }

        manager.resumeActiveJobs()
        blockedPendingUpdate.release.countDown()
        assertEquals("call-resume-live-pending" to "live pending", toolApi.awaitRequest("call-resume-live-pending"))
        toolApi.complete(response(callId = "call-resume-live-pending", answer = "live pending answer"))
        conversationStore.awaitHermesRecord("call-resume-live-pending") {
            it.status == HermesQueueStatus.Complete && it.answer == "live pending answer"
        }

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-resume-live-pending" }
        assertEquals(1, records.size)
        assertFalse(records.any { it.status == HermesQueueStatus.Expired })
    }

    @Test
    fun `resume skips same manager active pending submit with custom active key`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val blockedPendingUpdate = conversationStore.blockAfterNextUpdate()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(
            callId = "call-resume-custom-pending",
            prompt = "custom pending",
            activeKey = "session-1:call-resume-custom-pending",
        )
        assertTrue(blockedPendingUpdate.started.await(500, TimeUnit.MILLISECONDS))
        conversationStore.awaitHermesRecord("call-resume-custom-pending") {
            it.status == HermesQueueStatus.Pending && it.jobId == null
        }

        manager.resumeActiveJobs()
        blockedPendingUpdate.release.countDown()
        assertEquals("call-resume-custom-pending" to "custom pending", toolApi.awaitRequest("call-resume-custom-pending"))
        toolApi.complete(response(callId = "call-resume-custom-pending", answer = "custom pending answer"))
        conversationStore.awaitHermesRecord("call-resume-custom-pending") {
            it.status == HermesQueueStatus.Complete && it.answer == "custom pending answer"
        }

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-resume-custom-pending" }
        assertEquals(1, records.size)
        assertFalse(records.any { it.status == HermesQueueStatus.Expired })
        assertEquals(1, toolApi.requests.count { it.first == "call-resume-custom-pending" })
    }

    @Test
    fun `resume does not start duplicate poller for live submitted job`() = runTest {
        val toolApi = BlockingPollVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        assertTrue(manager.submit(callId = "call-live-resume", prompt = "live resume", activeKey = "session-1:call-live-resume"))
        conversationStore.awaitHermesRecord("call-live-resume") {
            it.jobId == "job-live-resume" && it.status == HermesQueueStatus.Queued
        }
        toolApi.firstPollStarted.await()

        manager.resumeActiveJobs()
        delay(50)

        assertEquals(1, toolApi.pollStarts.get())
        toolApi.releasePoll.complete(Unit)
        conversationStore.awaitHermesRecord("call-live-resume") {
            it.jobId == "job-live-resume" &&
                it.status == HermesQueueStatus.Complete &&
                it.answer == "live resume answer"
        }
    }

    @Test
    fun `resume ignores stale active duplicate superseded by terminal record`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-stale-active-duplicate",
                prompt = "old running prompt",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-stale-active",
            )
        }.withDuplicateHermesTerminal(
            callId = "call-stale-active-duplicate",
            prompt = "latest failed prompt",
            status = HermesQueueStatus.Failed,
            outputText = "latest failure",
        )
        val toolApi = FakeVoiceToolApi().apply {
            scriptPollSucceeded(
                jobId = "job-stale-active",
                callId = "call-stale-active-duplicate",
                answer = "stale answer",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.resumeActiveJobs()
        delay(50)

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-stale-active-duplicate" }
        assertEquals(listOf(HermesQueueStatus.Running, HermesQueueStatus.Failed), records.map { it.status })
        assertEquals(0, toolApi.pollCount("call-stale-active-duplicate"))
    }

    @Test
    fun `resume polls distinct active records when call id has multiple active jobs`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-reused-active",
                    prompt = "old active prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-old-active",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-reused-active",
                    prompt = "new active prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-new-active",
                )
            }
        val toolApi = FakeVoiceToolApi().apply {
            scriptPollSucceeded(jobId = "job-old-active", callId = "call-reused-active", answer = "old active answer")
            scriptPollSucceeded(jobId = "job-new-active", callId = "call-reused-active", answer = "new active answer")
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.resumeActiveJobs()
        conversationStore.awaitHermesRecord("call-reused-active") {
            it.jobId == "job-old-active" && it.status == HermesQueueStatus.Complete
        }
        conversationStore.awaitHermesRecord("call-reused-active") {
            it.jobId == "job-new-active" && it.status == HermesQueueStatus.Complete
        }

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-reused-active" }
        assertEquals(listOf("job-old-active", "job-new-active"), records.map { it.jobId })
        assertEquals(listOf(HermesQueueStatus.Complete, HermesQueueStatus.Complete), records.map { it.status })
        assertEquals(2, toolApi.pollCount("call-reused-active"))
    }

    @Test
    fun `job created side effects are skipped when submit loses to explicit cancel`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val diagnostics = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val queueEvents = Collections.synchronizedList(mutableListOf<String>())
        val blockedSubmit = toolApi.blockSubmit("call-cancel-before-created")
        val manager = manager(
            toolApi = toolApi,
            conversationStore = conversationStore,
            scope = this,
            recordDiagnostic = { event, detail -> diagnostics += event to detail },
            writeQueueEvent = queueEvents::add,
        )

        manager.submit(callId = "call-cancel-before-created", prompt = "cancel before created")
        assertTrue(blockedSubmit.started.await(500, TimeUnit.MILLISECONDS))
        manager.cancel("call-cancel-before-created")
        conversationStore.awaitHermesRecord("call-cancel-before-created") {
            it.status == HermesQueueStatus.Canceled
        }

        blockedSubmit.release.countDown()
        toolApi.awaitRemoteCancelled("call-cancel-before-created")
        delay(50)

        assertTrue(diagnostics.isEmpty())
        assertTrue(queueEvents.isEmpty())
    }

    @Test
    fun `cancel before submit returns cancels remote job after job id arrives`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val blockedSubmit = toolApi.blockSubmitCancellable("call-cancel-job-id-race")
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-cancel-job-id-race", prompt = "cancel job id race")
        assertTrue(blockedSubmit.started.await(500, TimeUnit.MILLISECONDS))
        manager.cancel("call-cancel-job-id-race")
        conversationStore.awaitHermesRecord("call-cancel-job-id-race") {
            it.status == HermesQueueStatus.Canceled
        }

        blockedSubmit.release.complete(Unit)
        toolApi.awaitRemoteCancelled("call-cancel-job-id-race")
        delay(50)

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-cancel-job-id-race" }
        assertEquals(1, records.size)
        assertEquals(HermesQueueStatus.Canceled, records.single().status)
        assertEquals("job-1", records.single().jobId)
    }

    @Test
    fun `stale active poll does not resurrect terminal record`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-stale-active", prompt = "stale active")
        assertEquals("call-stale-active" to "stale active", toolApi.awaitRequest("call-stale-active"))
        val queuedRecord = conversationStore.awaitHermesRecord("call-stale-active") {
            it.status == HermesQueueStatus.Queued && it.jobId != null
        }

        conversationStore.update {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-stale-active",
                prompt = "stale active",
                status = VoiceToolRecordStatus.Canceled("already canceled"),
                jobId = queuedRecord.jobId,
            )
        }
        toolApi.scriptQueuedPolls(callId = "call-stale-active", count = 1)
        delay(50)

        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-stale-active" }
        assertEquals(1, records.size)
        assertEquals(HermesQueueStatus.Canceled, records.single().status)
    }

    @Test
    fun `repeated bridge attach announces each completed result once`() = runTest {
        val conversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-announced-once",
                prompt = "announce once",
                status = VoiceToolRecordStatus.Complete("single answer"),
                jobId = "job-once",
                resultAnnounced = false,
            )
        }
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(conversation)
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 1L)
        manager.attachBridge(bridge = bridge, sessionId = 1L)
        conversationStore.awaitHermesRecord("call-announced-once") {
            it.resultAnnounced
        }

        assertEquals(1, bridge.completionFollowUps.size)
        assertEquals("single answer", bridge.completionFollowUps.single().answer)
    }

    @Test
    fun `completion announcement uses latest matching durable record`() = runTest {
        val conversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-duplicate-complete",
                prompt = "old prompt",
                status = VoiceToolRecordStatus.Complete("old answer"),
                jobId = "job-duplicate",
                resultAnnounced = false,
            )
        }.withDuplicateHermesCompletion(
            callId = "call-duplicate-complete",
            prompt = "latest prompt",
            answer = "latest answer",
        )
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(conversation)
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 3L)
        conversationStore.awaitHermesRecord("call-duplicate-complete") {
            it.resultAnnounced
        }

        assertEquals(
            listOf(
                CompletionFollowUp(
                    callId = "call-duplicate-complete",
                    prompt = "latest prompt",
                    answer = "latest answer",
                    sessionId = 3L,
                )
            ),
            bridge.completionFollowUps,
        )
        val records = conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.callId == "call-duplicate-complete" }
        assertEquals(2, records.size)
        assertTrue(records.all { it.resultAnnounced })
    }

    @Test
    fun `bridge attach ignores stale completed duplicate superseded by later terminal record`() = runTest {
        val conversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-stale-complete",
                prompt = "old prompt",
                status = VoiceToolRecordStatus.Complete("old answer"),
                jobId = "job-stale",
                resultAnnounced = false,
            )
        }.withDuplicateHermesTerminal(
            callId = "call-stale-complete",
            prompt = "latest prompt",
            status = HermesQueueStatus.Failed,
            outputText = "latest failure",
        )
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(conversation)
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 4L)
        delay(50)

        assertTrue(bridge.completionFollowUps.isEmpty())
        val snapshot = HermesQueueSnapshot.from(conversationStore.conversation.value)
        assertTrue(snapshot.unannouncedTerminal.isEmpty())
        assertEquals(listOf(HermesQueueStatus.Failed), snapshot.announcedTerminal.map { it.status })
        assertEquals(1, bridge.terminalFollowUps.size)
        assertEquals("latest failure", bridge.terminalFollowUps.single().reason)
    }

    @Test
    fun `repeated bridge attach announces failed terminal result once`() = runTest {
        val conversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-failed-once",
                prompt = "announce failure once",
                status = VoiceToolRecordStatus.Failed("single failure"),
                jobId = "job-failed-once",
                resultAnnounced = false,
            )
        }
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore(conversation)
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 5L)
        manager.attachBridge(bridge = bridge, sessionId = 5L)
        conversationStore.awaitHermesRecord("call-failed-once") {
            it.resultAnnounced
        }

        assertEquals(1, bridge.terminalFollowUps.size)
        assertEquals(
            TerminalFollowUp(
                callId = "call-failed-once",
                prompt = "announce failure once",
                status = HermesQueueStatus.Failed,
                reason = "single failure",
                sessionId = 5L,
            ),
            bridge.terminalFollowUps.single(),
        )
    }

    @Test
    fun `concurrent completions preserve both durable records`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = SnapshotBeforeBlockConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-a", prompt = "first request")
        assertEquals("call-a" to "first request", toolApi.awaitRequest("call-a"))
        manager.submit(callId = "call-b", prompt = "second request")
        assertEquals("call-b" to "second request", toolApi.awaitRequest("call-b"))
        conversationStore.awaitHermesRecord("call-a") {
            it.status == HermesQueueStatus.Queued
        }
        conversationStore.awaitHermesRecord("call-b") {
            it.status == HermesQueueStatus.Queued
        }

        val blockedUpdate = conversationStore.blockNextUpdate()
        toolApi.complete(response(callId = "call-a", answer = "answer a"))
        blockedUpdate.started.await()
        toolApi.complete(response(callId = "call-b", answer = "answer b"))
        delay(50)
        blockedUpdate.release.complete(Unit)

        conversationStore.awaitHermesRecord("call-a") {
            it.status == HermesQueueStatus.Complete && it.answer == "answer a"
        }
        conversationStore.awaitHermesRecord("call-b") {
            it.status == HermesQueueStatus.Complete && it.answer == "answer b"
        }

        val records = conversationStore.conversation.value.hermesQueueRecords()
        assertEquals(listOf("call-a", "call-b"), records.map { it.callId }.sorted())
    }

    @Test
    fun `queue store samples session id before waiting for every persistence update`() = runTest {
        val cases = listOf(
            QueueStoreSessionTimingCase(
                id = "active",
                expectedStatus = HermesQueueStatus.Running,
                persist = { store, callId, prompt ->
                    store.persistActiveIfStillActive(
                        callId = callId,
                        prompt = prompt,
                        status = VoiceToolRecordStatus.Running,
                        jobId = "job-$callId",
                    )
                },
            ),
            QueueStoreSessionTimingCase(
                id = "pending",
                expectedStatus = HermesQueueStatus.Pending,
                persist = { store, callId, prompt ->
                    store.persistPendingIfStillActive(
                        callId = callId,
                        prompt = prompt,
                    )
                },
            ),
            QueueStoreSessionTimingCase(
                id = "canceled",
                expectedStatus = HermesQueueStatus.Canceled,
                persist = { store, callId, prompt ->
                    store.persistCanceledIfStillActive(
                        callId = callId,
                        prompt = prompt,
                        jobId = "job-$callId",
                        message = "canceled",
                    )
                },
            ),
            QueueStoreSessionTimingCase(
                id = "terminal",
                expectedStatus = HermesQueueStatus.Complete,
                persist = { store, callId, prompt ->
                    store.persistTerminalIfStillActive(
                        callId = callId,
                        prompt = prompt,
                        status = VoiceToolRecordStatus.Complete("answer"),
                        jobId = "job-$callId",
                    )
                },
            ),
        )

        cases.forEach { case ->
            val conversationStore = SnapshotBeforeBlockConversationStore()
            var currentSessionId = "session-a"
            val providerCalls = AtomicInteger(0)
            val secondSessionSampled = CompletableDeferred<Unit>()
            val queueStore = HermesQueueStore(
                conversationStore = conversationStore,
                persister = persister,
                persistenceSessionId = {
                    val sessionId = currentSessionId
                    if (providerCalls.incrementAndGet() == 2) {
                        secondSessionSampled.complete(Unit)
                    }
                    sessionId
                },
            )
            val blockedUpdate = conversationStore.blockNextUpdate()

            val blockedPersist = launch {
                assertTrue(
                    case.persist(
                        queueStore,
                        "call-blocked-${case.id}",
                        "blocked ${case.id} prompt",
                    )
                )
            }
            blockedUpdate.started.await()

            val sampledCallId = "call-sampled-${case.id}"
            val sampledPersist = launch {
                assertTrue(
                    case.persist(
                        queueStore,
                        sampledCallId,
                        "sampled ${case.id} prompt",
                    )
                )
            }
            withTimeout(500) {
                secondSessionSampled.await()
            }
            currentSessionId = "session-b"
            blockedUpdate.release.complete(Unit)

            blockedPersist.join()
            sampledPersist.join()

            val sampledTool = conversationStore.conversation.value.currentMessages
                .flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Tool>()
                .single { it.toolCallId == sampledCallId }
            val sampledRecord = conversationStore.conversation.value.hermesQueueRecords()
                .single { it.callId == sampledCallId }

            assertEquals(case.expectedStatus, sampledRecord.status)
            assertEquals(
                "session-a",
                sampledTool.metadata!!["voice_session_id"]!!.jsonPrimitive.content,
            )
        }
    }

    private fun manager(
        toolApi: VoiceToolApi,
        conversationStore: VoiceConversationStore,
        scope: CoroutineScope,
        updateToolStatus: (VoiceToolStatus) -> Unit = {},
        recordDiagnostic: (String, String) -> Unit = { _, _ -> },
        writeQueueEvent: (String) -> Unit = {},
        writeHermesAnswer: (String) -> Unit = {},
        maxElapsedMs: Long = 1_000L,
        remoteCancelTimeoutMs: Long = 50L,
        bridgeSendTimeoutMs: Long = 200L,
        observability: VoiceObservability = NoOpVoiceObservability,
        traceContext: VoiceTraceContext = VoiceTraceContext(
            traceId = "trace-test",
            voiceSessionId = "session-test",
        ),
    ) = HermesJobManager(
        toolApi = toolApi,
        conversationStore = conversationStore,
        persister = persister,
        scope = scope,
        dispatcher = Dispatchers.Default,
        pollIntervalMs = 10L,
        pollRetryDelayMs = 1L,
        maxElapsedMs = maxElapsedMs,
        remoteCancelTimeoutMs = remoteCancelTimeoutMs,
        bridgeSendTimeoutMs = bridgeSendTimeoutMs,
        updateToolStatus = updateToolStatus,
        recordDiagnostic = recordDiagnostic,
        writeQueueEvent = writeQueueEvent,
        writeHermesAnswer = writeHermesAnswer,
        observability = observability,
        traceContext = traceContext,
    )

    private fun response(callId: String, answer: String) = MobileHermesResponse(
        callId = callId,
        answer = answer,
        model = "hermes-test",
        profileId = "profile-test",
        profileLabel = "Hermes Test",
        elapsedMs = 42L,
    )

    private fun Conversation.withHermesCreatedAt(callId: String, createdAt: String): Conversation {
        return updateCurrentMessages(
            currentMessages.map { message ->
                message.copy(
                    parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == callId) {
                            part.copy(metadata = part.metadata?.withCreatedAt(createdAt))
                        } else {
                            part
                        }
                    }
                )
            }
        )
    }

    private fun kotlinx.serialization.json.JsonObject.withCreatedAt(
        createdAt: String,
    ) = buildJsonObject {
        forEach { (key, value) -> put(key, value) }
        put(HERMES_TOOL_CREATED_AT_KEY, JsonPrimitive(createdAt))
    }

    private fun Conversation.withHermesUpdatedAt(callId: String, updatedAt: String): Conversation {
        return updateCurrentMessages(
            currentMessages.map { message ->
                message.copy(
                    parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == callId) {
                            part.copy(metadata = part.metadata?.withUpdatedAt(updatedAt))
                        } else {
                            part
                        }
                    }
                )
            }
        )
    }

    private fun kotlinx.serialization.json.JsonObject.withUpdatedAt(
        updatedAt: String,
    ) = buildJsonObject {
        forEach { (key, value) -> put(key, value) }
        put(HERMES_TOOL_UPDATED_AT_KEY, JsonPrimitive(updatedAt))
    }

    private fun Conversation.withDuplicateHermesCompletion(
        callId: String,
        prompt: String,
        answer: String,
    ): Conversation {
        val sourceTool = currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .last { it.toolCallId == callId }
        val duplicateTool = sourceTool.copy(
            input = buildJsonObject {
                put("prompt", JsonPrimitive(prompt))
            }.toString(),
            output = sourceTool.output.map { part ->
                if (part is UIMessagePart.Text) {
                    part.copy(
                        text = answer,
                        metadata = part.metadata?.withResultAnnounced(false),
                    )
                } else {
                    part
                }
            },
            metadata = sourceTool.metadata?.withResultAnnounced(false),
        )
        return updateCurrentMessages(
            currentMessages + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(duplicateTool),
            )
        )
    }

    private fun Conversation.withDuplicateHermesTerminal(
        callId: String,
        prompt: String,
        status: HermesQueueStatus,
        outputText: String,
    ): Conversation {
        val sourceTool = currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .last { it.toolCallId == callId }
        val duplicateTool = sourceTool.copy(
            input = buildJsonObject {
                put("prompt", JsonPrimitive(prompt))
            }.toString(),
            output = sourceTool.output.map { part ->
                if (part is UIMessagePart.Text) {
                    part.copy(
                        text = outputText,
                        metadata = part.metadata
                            ?.withStatus(status)
                            ?.withResultAnnounced(false),
                    )
                } else {
                    part
                }
            },
            metadata = sourceTool.metadata
                ?.withStatus(status)
                ?.withResultAnnounced(false),
        )
        return updateCurrentMessages(
            currentMessages + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(duplicateTool),
            )
        )
    }

    private fun kotlinx.serialization.json.JsonObject.withResultAnnounced(
        resultAnnounced: Boolean,
    ) = buildJsonObject {
        forEach { (key, value) -> put(key, value) }
        put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, JsonPrimitive(resultAnnounced))
    }

    private fun kotlinx.serialization.json.JsonObject.withStatus(
        status: HermesQueueStatus,
    ) = buildJsonObject {
        forEach { (key, value) -> put(key, value) }
        put(HERMES_TOOL_STATUS_KEY, JsonPrimitive(status.wireName))
    }

    private suspend fun VoiceConversationStore.awaitHermesRecord(
        callId: String,
        predicate: (HermesQueueRecord) -> Boolean,
    ): HermesQueueRecord = withTimeout(500) {
        while (true) {
            conversation.value.hermesQueueRecords()
                .firstOrNull { it.callId == callId && predicate(it) }
                ?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable")
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}

private class SnapshotBeforeBlockConversationStore(
    initialConversation: Conversation = Conversation.ofId(id = Uuid.random()),
) : VoiceConversationStore {
    private val lock = Any()
    private var blockedUpdate: BlockedSnapshotUpdate? = null
    override val conversation: StateFlow<Conversation> = MutableStateFlow(initialConversation)

    override suspend fun update(transform: (Conversation) -> Conversation) {
        val flow = conversation as MutableStateFlow<Conversation>
        val snapshot = flow.value
        val blocked = synchronized(lock) {
            blockedUpdate.also { blockedUpdate = null }
        }
        if (blocked != null) {
            blocked.started.complete(Unit)
            blocked.release.await()
        }
        flow.value = transform(snapshot)
    }

    fun blockNextUpdate(): BlockedSnapshotUpdate {
        return BlockedSnapshotUpdate().also { blocked ->
            synchronized(lock) {
                blockedUpdate = blocked
            }
        }
    }
}

private class BlockedSnapshotUpdate {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

private data class QueueStoreSessionTimingCase(
    val id: String,
    val expectedStatus: HermesQueueStatus,
    val persist: suspend (HermesQueueStore, String, String) -> Boolean,
)

private class BlockingPollVoiceToolApi : VoiceToolApi {
    val firstPollStarted = CompletableDeferred<Unit>()
    val releasePoll = CompletableDeferred<Unit>()
    val pollStarts = AtomicInteger(0)

    override suspend fun submitHermesJob(callId: String, prompt: String): MobileHermesJobSubmitResponse {
        return MobileHermesJobSubmitResponse(
            jobId = "job-live-resume",
            callId = callId,
            status = "queued",
            createdAt = "2026-06-11T00:00:00.000Z",
        )
    }

    override suspend fun getHermesJob(jobId: String): MobileHermesJobPollResponse {
        pollStarts.incrementAndGet()
        firstPollStarted.complete(Unit)
        releasePoll.await()
        return MobileHermesJobPollResponse(
            jobId = jobId,
            callId = "call-live-resume",
            status = "succeeded",
            answer = "live resume answer",
            model = "hermes-agent",
            profileId = "default",
            profileLabel = "Default",
            elapsedMs = 123,
            createdAt = "2026-06-11T00:00:00.000Z",
            completedAt = "2026-06-11T00:00:01.000Z",
        )
    }

    override suspend fun cancelHermesJob(jobId: String): MobileHermesJobPollResponse {
        return MobileHermesJobPollResponse(
            jobId = jobId,
            callId = "call-live-resume",
            status = "canceled",
            error = "Hermes job canceled",
            createdAt = "2026-06-11T00:00:00.000Z",
            completedAt = "2026-06-11T00:00:01.000Z",
        )
    }
}

private class RecordingHermesBridge : HermesSessionBridge {
    val queuedAcknowledgements = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    val completionFollowUps = Collections.synchronizedList(mutableListOf<CompletionFollowUp>())
    val terminalFollowUps = Collections.synchronizedList(mutableListOf<TerminalFollowUp>())
    var failCompletionFollowUp = false
    var failTerminalFollowUp = false
    var failQueuedAcknowledgement = false
    var throwQueuedAcknowledgement = false
    private val blockedQueuedAcknowledgements = Collections.synchronizedList(mutableListOf<BlockedBridgeCall>())
    private val blockedCompletionFollowUps = Collections.synchronizedList(mutableListOf<BlockedCompletionFollowUp>())

    override fun sendQueuedAcknowledgement(callId: String, sessionId: Long): Boolean {
        val blocked = blockedQueuedAcknowledgements.removeFirstOrNull()
        if (blocked != null) {
            blocked.started.countDown()
            blocked.release.await(blocked.timeoutMillis, TimeUnit.MILLISECONDS)
        }
        if (throwQueuedAcknowledgement) error("queued acknowledgement failed")
        if (failQueuedAcknowledgement) return false
        queuedAcknowledgements += callId to sessionId
        return true
    }

    override fun sendCompletionFollowUp(
        callId: String,
        prompt: String,
        answer: String,
        sessionId: Long,
    ): Boolean {
        val blocked = blockedCompletionFollowUps.removeFirstOrNull()
        if (blocked != null) {
            blocked.started.complete(Unit)
            runBlocking { blocked.release.await() }
        }
        if (failCompletionFollowUp) return false
        completionFollowUps += CompletionFollowUp(
            callId = callId,
            prompt = prompt,
            answer = answer,
            sessionId = sessionId,
        )
        return true
    }

    override fun sendTerminalFollowUp(
        callId: String,
        prompt: String,
        status: HermesQueueStatus,
        reason: String,
        sessionId: Long,
    ): Boolean {
        if (failTerminalFollowUp) return false
        terminalFollowUps += TerminalFollowUp(
            callId = callId,
            prompt = prompt,
            status = status,
            reason = reason,
            sessionId = sessionId,
        )
        return true
    }

    fun blockNextCompletionFollowUp(): BlockedCompletionFollowUp {
        return BlockedCompletionFollowUp().also { blocked ->
            blockedCompletionFollowUps += blocked
        }
    }

    fun blockNextQueuedAcknowledgement(): BlockedBridgeCall {
        return BlockedBridgeCall().also { blocked ->
            blockedQueuedAcknowledgements += blocked
        }
    }

    suspend fun awaitQueuedAcknowledgements(count: Int) {
        withTimeout(500) {
            while (queuedAcknowledgements.size < count) {
                delay(10)
            }
        }
    }
}

private class BlockedBridgeCall {
    val started = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    var timeoutMillis: Long = 500
}

private class BlockedCompletionFollowUp {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

private data class CompletionFollowUp(
    val callId: String,
    val prompt: String,
    val answer: String,
    val sessionId: Long,
)

private data class TerminalFollowUp(
    val callId: String,
    val prompt: String,
    val status: HermesQueueStatus,
    val reason: String,
    val sessionId: Long,
)
