package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class LiveKitVoicePersistenceBridgeTest {
    @Test
    fun `job acceptance ack is returned only after durable queued record exists`() = runTest {
        val store = RecordingVoiceConversationStore(blockUpdates = true)
        val bridge = bridge(store)

        val ack = async { bridge.handle(AGENT_IDENTITY, acceptedEventJson()) }
        store.updateStarted.await()
        assertFalse(ack.isCompleted)
        store.releaseUpdate.complete(Unit)

        val parsed = parseLiveKitPersistenceAck(ack.await())!!
        assertEquals("persisted", parsed.status)
        val record = store.conversation.value.hermesQueueRecords().single()
        assertEquals("hj_1", record.jobId)
        assertEquals(REQUEST_HASH, record.requestHash)
        assertEquals(ARGUMENT_HASH, record.argumentHash)
        assertEquals("turn_1", record.originatingUserTurnId)
    }

    @Test
    fun `replayed event returns persisted without adding a second tool record`() = runTest {
        val store = RecordingVoiceConversationStore()
        val evidence = RecordingEvidenceSink()
        val bridge = bridge(store, evidence)

        val first = bridge.handle(AGENT_IDENTITY, acceptedEventJson())
        val second = bridge.handle(AGENT_IDENTITY, acceptedEventJson())

        assertEquals(
            parseLiveKitPersistenceAck(first)!!.eventId,
            parseLiveKitPersistenceAck(second)!!.eventId,
        )
        assertEquals(1, store.conversation.value.hermesQueueRecords().size)
        assertEquals(listOf("evt_accepted"), evidence.events.map { it.eventId })
    }

    @Test
    fun `clarification Hermes result and grounded presentation remain separate roles`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)

        bridge.handle(
            AGENT_IDENTITY,
            assistantTranscriptJson(text = "clarification", eventId = "evt_clarification"),
        )
        bridge.handle(
            AGENT_IDENTITY,
            succeededEventJson(answer = "Hermes answer"),
        )
        bridge.handle(
            AGENT_IDENTITY,
            assistantTranscriptJson(
                text = "grounded presentation",
                eventId = "evt_grounded",
                groundedJobId = "hj_1",
                groundedResultHash = voiceSha256("Hermes answer"),
            ),
        )

        val messages = store.conversation.value.currentMessages
        assertEquals(3, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages[0].role)
        assertTrue(messages[1].parts.single() is UIMessagePart.Tool)
        val tool = messages[1].parts.single() as UIMessagePart.Tool
        assertEquals("Hermes answer", (tool.output.single() as UIMessagePart.Text).text)
        assertEquals("hermes", tool.metadata!!["voice_producer"]!!.jsonPrimitive.content)
        assertEquals(voiceSha256("Hermes answer"), tool.metadata!!["voice_result_hash"]!!.jsonPrimitive.content)
        assertEquals(MessageRole.ASSISTANT, messages[2].role)
        assertEquals("hj_1", messages[2].groundedJobId())
        assertEquals(
            voiceSha256("Hermes answer"),
            messages[2].groundedResultHash(),
        )
    }

    @Test
    fun `running still working terminal and announcement events update one Hermes record`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)

        bridge.handle(AGENT_IDENTITY, acceptedEventJson())
        bridge.handle(AGENT_IDENTITY, jobStateJson(kind = "job_running", eventId = "evt_running"))
        bridge.handle(AGENT_IDENTITY, jobStateJson(kind = "still_working", eventId = "evt_working"))
        assertTrue(store.conversation.value.hermesQueueRecords().single().stillWorkingAnnounced)
        bridge.handle(
            AGENT_IDENTITY,
            succeededEventJson(answer = "Hermes answer"),
        )
        bridge.handle(AGENT_IDENTITY, deliveryAnnouncedJson())

        val records = store.conversation.value.hermesQueueRecords()
        assertEquals(1, records.size)
        assertEquals("complete", records.single().status.wireName)
        assertTrue(records.single().resultAnnounced)
        assertEquals(RESULT_HASH, records.single().resultHash)
    }

    @Test
    fun `wrong caller and wrong session are rejected before persistence`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)

        val callerFailure = runCatching {
            bridge.handle("unexpected-agent", acceptedEventJson())
        }.exceptionOrNull()
        val sessionFailure = runCatching {
            bridge.handle(
                AGENT_IDENTITY,
                acceptedEventJson().replace(VOICE_SESSION_ID, "another_session"),
            )
        }.exceptionOrNull()

        assertTrue(callerFailure is IllegalArgumentException)
        assertTrue(sessionFailure is IllegalArgumentException)
        assertTrue(store.conversation.value.currentMessages.isEmpty())
    }

    @Test
    fun `evidence only delivery event does not mutate conversation`() = runTest {
        val store = RecordingVoiceConversationStore()
        val evidence = RecordingEvidenceSink()
        val bridge = bridge(store, evidence)

        val ack = bridge.handle(AGENT_IDENTITY, deliveryEligibleJson())

        assertEquals("persisted", parseLiveKitPersistenceAck(ack)!!.status)
        assertTrue(store.conversation.value.currentMessages.isEmpty())
        assertEquals(listOf("delivery_eligible"), evidence.events.map { it.kind })
    }

    @Test
    fun `job state cannot overwrite accepted immutable correlation`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)
        bridge.handle(AGENT_IDENTITY, acceptedEventJson())

        val failure = runCatching {
            bridge.handle(
                AGENT_IDENTITY,
                jobStateJson(kind = "job_running", eventId = "evt_mismatch")
                    .replace(REQUEST_HASH, "sha256:${"3".repeat(64)}"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        val record = store.conversation.value.hermesQueueRecords().single()
        assertEquals(REQUEST_HASH, record.requestHash)
        assertEquals(ARGUMENT_HASH, record.argumentHash)
        assertEquals("turn_1", record.originatingUserTurnId)
    }

    @Test
    fun `grounded assistant transcript requires the matching completed Hermes result`() = runTest {
        val missingStore = RecordingVoiceConversationStore()
        val missingBridge = bridge(missingStore)
        val missingFailure = runCatching {
            missingBridge.handle(
                AGENT_IDENTITY,
                assistantTranscriptJson(
                    text = "unsupported presentation",
                    eventId = "evt_missing_grounding",
                    groundedJobId = "hj_1",
                    groundedResultHash = RESULT_HASH,
                ),
            )
        }.exceptionOrNull()

        val mismatchStore = RecordingVoiceConversationStore()
        val mismatchBridge = bridge(mismatchStore)
        mismatchBridge.handle(AGENT_IDENTITY, succeededEventJson(answer = "Hermes answer"))
        val mismatchFailure = runCatching {
            mismatchBridge.handle(
                AGENT_IDENTITY,
                assistantTranscriptJson(
                    text = "wrong presentation",
                    eventId = "evt_wrong_grounding",
                    groundedJobId = "hj_1",
                    groundedResultHash = "sha256:${"4".repeat(64)}",
                ),
            )
        }.exceptionOrNull()

        assertTrue(missingFailure is IllegalArgumentException)
        assertTrue(missingStore.conversation.value.currentMessages.isEmpty())
        assertTrue(mismatchFailure is IllegalArgumentException)
        assertEquals(1, mismatchStore.conversation.value.currentMessages.size)
        assertTrue(mismatchStore.conversation.value.currentMessages.single().parts.single() is UIMessagePart.Tool)
    }

    private fun bridge(
        store: VoiceConversationStore,
        evidence: VoiceExperienceEvidenceSink = RecordingEvidenceSink(),
    ): LiveKitVoicePersistenceBridge {
        val transcriptPersister = VoiceTranscriptPersister()
        return LiveKitVoicePersistenceBridge(
            voiceSessionId = VOICE_SESSION_ID,
            agentIdentity = AGENT_IDENTITY,
            queueStore = HermesQueueStore(
                conversationStore = store,
                writer = HermesToolRecordWriter(nowIso = { PERSISTED_AT }),
                transcriptPersister = transcriptPersister,
                persistenceSessionId = { VOICE_SESSION_ID },
            ),
            transcriptPersister = transcriptPersister,
            conversationStore = store,
            evidence = evidence,
            now = { Instant.parse(PERSISTED_AT) },
        )
    }
}

private class RecordingVoiceConversationStore(
    private val blockUpdates: Boolean = false,
) : VoiceConversationStore {
    private val state = MutableStateFlow(Conversation.ofId(Uuid.random()))
    val updateStarted = CompletableDeferred<Unit>()
    val releaseUpdate = CompletableDeferred<Unit>()

    override val conversation: StateFlow<Conversation> = state

    override suspend fun update(transform: (Conversation) -> Conversation) {
        updateStarted.complete(Unit)
        if (blockUpdates) releaseUpdate.await()
        state.value = transform(state.value)
    }
}

private class RecordingEvidenceSink : VoiceExperienceEvidenceSink {
    val events = mutableListOf<LiveKitVoiceExperienceEvent>()

    override suspend fun append(event: LiveKitVoiceExperienceEvent) {
        events += event
    }
}

private fun me.rerere.ai.ui.UIMessage.groundedJobId(): String? =
    (parts.single() as UIMessagePart.Text)
        .metadata
        ?.get("voice_grounded_job_id")
        ?.jsonPrimitive
        ?.content

private fun me.rerere.ai.ui.UIMessage.groundedResultHash(): String? =
    (parts.single() as UIMessagePart.Text)
        .metadata
        ?.get("voice_grounded_result_hash")
        ?.jsonPrimitive
        ?.content

private fun acceptedEventJson(): String =
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_accepted","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"turn_1","requestHash":"$REQUEST_HASH","toolCallId":"call_1","argumentHash":"$ARGUMENT_HASH","jobId":"hj_1","prompt":"private question"}"""

private fun succeededEventJson(answer: String): String =
    jobStateJson(
        kind = "job_succeeded",
        eventId = "evt_succeeded",
        suffix = ""","resultHash":"${voiceSha256(answer)}","answer":"$answer"""",
    )

private fun jobStateJson(
    kind: String,
    eventId: String,
    suffix: String = "",
): String =
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"$kind","observedAt":"2026-07-30T12:00:01Z","userTurnId":"turn_1","requestHash":"$REQUEST_HASH","toolCallId":"call_1","argumentHash":"$ARGUMENT_HASH","jobId":"hj_1"$suffix}"""

private fun assistantTranscriptJson(
    text: String,
    eventId: String,
    groundedJobId: String? = null,
    groundedResultHash: String? = null,
): String {
    val grounding = if (groundedJobId != null && groundedResultHash != null) {
        ""","groundedJobId":"$groundedJobId","groundedResultHash":"$groundedResultHash""""
    } else {
        ""
    }
    return """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"transcript","observedAt":"2026-07-30T12:00:02Z","turnId":"${eventId}_turn","role":"assistant","text":"$text","interrupted":false$grounding}"""
}

private fun deliveryEligibleJson(): String =
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_eligible","kind":"delivery_eligible","observedAt":"2026-07-30T12:00:03Z","toolCallId":"call_1","jobId":"hj_1"}"""

private fun deliveryAnnouncedJson(): String =
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_announced","kind":"delivery_announced","observedAt":"2026-07-30T12:00:04Z","toolCallId":"call_1","jobId":"hj_1","assistantTurnId":"assistant_1"}"""

private const val VOICE_SESSION_ID = "lvs_1"
private const val AGENT_IDENTITY = "agent_1"
private const val PERSISTED_AT = "2026-07-30T12:00:05Z"
private const val REQUEST_HASH =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
private const val ARGUMENT_HASH =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"
private const val RESULT_HASH =
    "sha256:5ba14662f757c0819a81f38b78c10d7b8cf7dda9ef6014b3ca7c25b5b7711d77"
