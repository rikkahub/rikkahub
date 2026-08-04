package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
        bridge.handle(
            AGENT_IDENTITY,
            assistantTranscriptJson(
                text = "grounded presentation",
                eventId = "evt_announced_presentation",
                groundedJobId = "hj_1",
                groundedResultHash = RESULT_HASH,
            ),
        )
        bridge.handle(
            AGENT_IDENTITY,
            deliveryAnnouncedJson(assistantTurnId = "evt_announced_presentation_turn"),
        )

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
    fun `follow up correlation is acknowledged into evidence without mutating conversation or queue`() = runTest {
        val store = RecordingVoiceConversationStore()
        val evidence = RecordingEvidenceSink()
        val bridge = bridge(store, evidence)

        val ack = bridge.handle(AGENT_IDENTITY, followUpCorrelationJson())

        assertEquals("persisted", parseLiveKitPersistenceAck(ack)!!.status)
        assertTrue(store.conversation.value.currentMessages.isEmpty())
        assertTrue(store.conversation.value.hermesQueueRecords().isEmpty())
        val event = evidence.events.single() as LiveKitVoiceExperienceEvent.FollowUpCorrelation
        assertEquals("turn_2", event.followUpTurnId)
        assertEquals("assistant_2", event.assistantTurnId)
        assertEquals(RESULT_HASH, event.resultHash)
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
    fun `running state cannot change accepted room hash or mutate queued record`() = runTest {
        val store = RecordingVoiceConversationStore()
        val evidence = RecordingEvidenceSink()
        val bridge = bridge(store, evidence)
        bridge.handle(AGENT_IDENTITY, acceptedEventJson())

        val failure = runCatching {
            bridge.handle(
                AGENT_IDENTITY,
                jobStateJson(
                    kind = "job_running",
                    eventId = "evt_changed_room",
                    roomHash = hash('5'),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        val record = store.conversation.value.hermesQueueRecords().single()
        assertEquals("queued", record.status.wireName)
        assertEquals("private question", record.prompt)
        assertEquals(listOf("evt_accepted"), evidence.events.map { it.eventId })
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

    @Test
    fun `delivery announcement requires its exact persisted grounded assistant turn`() = runTest {
        val phantomStore = RecordingVoiceConversationStore()
        val phantomBridge = bridge(phantomStore)
        phantomBridge.handle(AGENT_IDENTITY, succeededEventJson(answer = "Hermes answer"))
        val phantomFailure = runCatching {
            phantomBridge.handle(
                AGENT_IDENTITY,
                deliveryAnnouncedJson(assistantTurnId = "phantom_turn"),
            )
        }.exceptionOrNull()

        val unrelatedStore = RecordingVoiceConversationStore()
        val unrelatedBridge = bridge(unrelatedStore)
        unrelatedBridge.handle(AGENT_IDENTITY, succeededEventJson(answer = "Hermes answer"))
        unrelatedBridge.handle(
            AGENT_IDENTITY,
            assistantTranscriptJson(
                text = "ordinary assistant turn",
                eventId = "evt_unrelated",
            ),
        )
        val unrelatedFailure = runCatching {
            unrelatedBridge.handle(
                AGENT_IDENTITY,
                deliveryAnnouncedJson(assistantTurnId = "evt_unrelated_turn"),
            )
        }.exceptionOrNull()

        assertTrue(phantomFailure is IllegalArgumentException)
        assertFalse(phantomStore.conversation.value.hermesQueueRecords().single().resultAnnounced)
        assertTrue(unrelatedFailure is IllegalArgumentException)
        assertFalse(unrelatedStore.conversation.value.hermesQueueRecords().single().resultAnnounced)
    }

    @Test
    fun `prior session grounded turn cannot authorize delivery when store session provider is null`() = runTest {
        val store = RecordingVoiceConversationStore()
        bridge(store).handle(
            AGENT_IDENTITY,
            succeededEventJson(answer = "Hermes answer"),
        )
        store.update { conversation ->
            VoiceTranscriptPersister().upsertAssistantTranscriptTurn(
                conversation = conversation,
                text = "prior session presentation",
                interrupted = false,
                turnId = "prior_session_turn",
                sessionId = "prior_session",
                groundedJobId = "hj_1",
                groundedResultHash = RESULT_HASH,
            )
        }
        val evidence = RecordingEvidenceSink()
        val bridgeWithoutStoreSession = bridge(
            store = store,
            evidence = evidence,
            queuePersistenceSessionId = null,
        )

        val failure = runCatching {
            bridgeWithoutStoreSession.handle(
                AGENT_IDENTITY,
                deliveryAnnouncedJson(assistantTurnId = "prior_session_turn"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(store.conversation.value.hermesQueueRecords().single().resultAnnounced)
        assertTrue(evidence.events.isEmpty())
    }

    @Test
    fun `conflicting terminal events are rejected without replacing the first terminal record`() = runTest {
        data class TerminalConflict(
            val first: String,
            val second: String,
            val expectedStatus: String,
            val expectedAnswer: String?,
            val expectedError: String?,
            val expectedResultHash: String?,
        )

        val conflicts = listOf(
            TerminalConflict(
                first = succeededEventJson(answer = "answer one", eventId = "evt_first_success"),
                second = succeededEventJson(answer = "answer two", eventId = "evt_second_success"),
                expectedStatus = "complete",
                expectedAnswer = "answer one",
                expectedError = null,
                expectedResultHash = ANSWER_ONE_HASH,
            ),
            TerminalConflict(
                first = succeededEventJson(answer = "answer one", eventId = "evt_success"),
                second = failedEventJson(reason = "safe failure", eventId = "evt_failed"),
                expectedStatus = "complete",
                expectedAnswer = "answer one",
                expectedError = null,
                expectedResultHash = ANSWER_ONE_HASH,
            ),
            TerminalConflict(
                first = failedEventJson(reason = "first safe reason", eventId = "evt_first_failure"),
                second = failedEventJson(reason = "different safe reason", eventId = "evt_second_failure"),
                expectedStatus = "failed",
                expectedAnswer = null,
                expectedError = "first safe reason",
                expectedResultHash = null,
            ),
        )

        conflicts.forEachIndexed { index, conflict ->
            val store = RecordingVoiceConversationStore()
            val evidence = RecordingEvidenceSink()
            val bridge = bridge(store, evidence)
            bridge.handle(AGENT_IDENTITY, conflict.first)

            val failure = runCatching {
                bridge.handle(AGENT_IDENTITY, conflict.second)
            }.exceptionOrNull()

            assertTrue("conflict $index", failure is IllegalArgumentException)
            val record = store.conversation.value.hermesQueueRecords().single()
            assertEquals("conflict $index", conflict.expectedStatus, record.status.wireName)
            assertEquals("conflict $index", conflict.expectedAnswer, record.answer)
            assertEquals("conflict $index", conflict.expectedError, record.error)
            assertEquals("conflict $index", conflict.expectedResultHash, record.resultHash)
            assertEquals("conflict $index", 1, evidence.events.size)
        }
    }

    @Test
    fun `semantically identical terminal event with a new event id is persisted once`() = runTest {
        val store = RecordingVoiceConversationStore()
        val evidence = RecordingEvidenceSink()
        val bridge = bridge(store, evidence)

        bridge.handle(
            AGENT_IDENTITY,
            succeededEventJson(answer = "Hermes answer", eventId = "evt_terminal_first"),
        )
        val ack = bridge.handle(
            AGENT_IDENTITY,
            succeededEventJson(answer = "Hermes answer", eventId = "evt_terminal_equivalent"),
        )

        assertEquals("persisted", parseLiveKitPersistenceAck(ack)!!.status)
        assertEquals(1, store.conversation.value.hermesQueueRecords().size)
        assertEquals(
            listOf("evt_terminal_first", "evt_terminal_equivalent"),
            evidence.events.map { it.eventId },
        )
    }

    @Test
    fun `second acceptance cannot change prompt or immutable correlation`() = runTest {
        val conflictingAcceptances = listOf(
            acceptedEventJson(eventId = "evt_second_prompt", prompt = "different private question"),
            acceptedEventJson(eventId = "evt_second_turn", userTurnId = "turn_2"),
            acceptedEventJson(
                eventId = "evt_second_request",
                requestHash = "sha256:${"5".repeat(64)}",
            ),
            acceptedEventJson(
                eventId = "evt_second_argument",
                argumentHash = "sha256:${"6".repeat(64)}",
            ),
        )

        conflictingAcceptances.forEachIndexed { index, conflictingPayload ->
            val store = RecordingVoiceConversationStore()
            val evidence = RecordingEvidenceSink()
            val bridge = bridge(store, evidence)
            bridge.handle(AGENT_IDENTITY, acceptedEventJson())

            val failure = runCatching {
                bridge.handle(AGENT_IDENTITY, conflictingPayload)
            }.exceptionOrNull()

            assertTrue("conflict $index", failure is IllegalArgumentException)
            val record = store.conversation.value.hermesQueueRecords().single()
            assertEquals("conflict $index", "private question", record.prompt)
            assertEquals("conflict $index", "turn_1", record.originatingUserTurnId)
            assertEquals("conflict $index", REQUEST_HASH, record.requestHash)
            assertEquals("conflict $index", ARGUMENT_HASH, record.argumentHash)
            assertEquals("conflict $index", 1, evidence.events.size)
        }
    }

    @Test
    fun `event id collision rejects changed content and changed kind`() = runTest {
        val contentStore = RecordingVoiceConversationStore()
        val contentEvidence = RecordingEvidenceSink()
        val contentBridge = bridge(contentStore, contentEvidence)
        contentBridge.handle(AGENT_IDENTITY, acceptedEventJson())
        val contentFailure = runCatching {
            contentBridge.handle(
                AGENT_IDENTITY,
                acceptedEventJson(prompt = "changed private question"),
            )
        }.exceptionOrNull()

        val kindStore = RecordingVoiceConversationStore()
        val kindEvidence = RecordingEvidenceSink()
        val kindBridge = bridge(kindStore, kindEvidence)
        kindBridge.handle(AGENT_IDENTITY, acceptedEventJson())
        val kindFailure = runCatching {
            kindBridge.handle(
                AGENT_IDENTITY,
                deliveryEligibleJson(eventId = "evt_accepted"),
            )
        }.exceptionOrNull()

        assertTrue(contentFailure is IllegalArgumentException)
        assertEquals("private question", contentStore.conversation.value.hermesQueueRecords().single().prompt)
        assertEquals(listOf("evt_accepted"), contentEvidence.events.map { it.eventId })
        assertTrue(kindFailure is IllegalArgumentException)
        assertEquals(1, kindStore.conversation.value.hermesQueueRecords().size)
        assertEquals(listOf("evt_accepted"), kindEvidence.events.map { it.eventId })
    }

    private fun bridge(
        store: VoiceConversationStore,
        evidence: VoiceExperienceEvidenceSink = RecordingEvidenceSink(),
        queuePersistenceSessionId: String? = VOICE_SESSION_ID,
    ): LiveKitVoicePersistenceBridge {
        val transcriptPersister = VoiceTranscriptPersister()
        return LiveKitVoicePersistenceBridge(
            voiceSessionId = VOICE_SESSION_ID,
            agentIdentity = AGENT_IDENTITY,
            queueStore = HermesQueueStore(
                conversationStore = store,
                writer = HermesToolRecordWriter(nowIso = { PERSISTED_AT }),
                transcriptPersister = transcriptPersister,
                persistenceSessionId = { queuePersistenceSessionId },
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

private fun acceptedEventJson(
    eventId: String = "evt_accepted",
    userTurnId: String = "turn_1",
    requestHash: String = REQUEST_HASH,
    argumentHash: String = ARGUMENT_HASH,
    prompt: String = "private question",
    ownerHash: String = OWNER_HASH,
    conversationHash: String = CONVERSATION_HASH,
    voiceSessionHash: String = VOICE_SESSION_HASH,
    roomHash: String = ROOM_HASH,
    traceHash: String = TRACE_HASH,
): String =
    canonicalJson("""{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"$userTurnId","requestHash":"$requestHash","toolCallId":"call_1","argumentHash":"$argumentHash","jobId":"hj_1","ownerHash":"$ownerHash","conversationHash":"$conversationHash","voiceSessionHash":"$voiceSessionHash","roomHash":"$roomHash","traceHash":"$traceHash","prompt":"$prompt"}""")

private fun succeededEventJson(
    answer: String,
    eventId: String = "evt_succeeded",
): String =
    jobStateJson(
        kind = "job_succeeded",
        eventId = eventId,
        suffix = ""","resultHash":"${voiceSha256(answer)}","answer":"$answer"""",
    )

private fun failedEventJson(reason: String, eventId: String): String =
    jobStateJson(
        kind = "job_failed",
        eventId = eventId,
        suffix = ""","failureReason":"$reason"""",
    )

private fun jobStateJson(
    kind: String,
    eventId: String,
    suffix: String = "",
    ownerHash: String = OWNER_HASH,
    conversationHash: String = CONVERSATION_HASH,
    voiceSessionHash: String = VOICE_SESSION_HASH,
    roomHash: String = ROOM_HASH,
    traceHash: String = TRACE_HASH,
): String =
    canonicalJson("""{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"$kind","observedAt":"2026-07-30T12:00:01Z","userTurnId":"turn_1","requestHash":"$REQUEST_HASH","toolCallId":"call_1","argumentHash":"$ARGUMENT_HASH","jobId":"hj_1","ownerHash":"$ownerHash","conversationHash":"$conversationHash","voiceSessionHash":"$voiceSessionHash","roomHash":"$roomHash","traceHash":"$traceHash"$suffix}""")

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
    return canonicalJson("""{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"transcript","observedAt":"2026-07-30T12:00:02Z","turnId":"${eventId}_turn","role":"assistant","text":"$text","interrupted":false$grounding}""")
}

private fun deliveryEligibleJson(eventId: String = "evt_eligible"): String =
    canonicalJson("""{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"delivery_eligible","observedAt":"2026-07-30T12:00:03Z","toolCallId":"call_1","jobId":"hj_1"}""")

private fun deliveryAnnouncedJson(
    assistantTurnId: String,
    eventId: String = "evt_announced",
): String =
    canonicalJson("""{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"delivery_announced","observedAt":"2026-07-30T12:00:04Z","toolCallId":"call_1","jobId":"hj_1","assistantTurnId":"$assistantTurnId"}""")

private fun followUpCorrelationJson(): String =
    canonicalJson("""{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_follow_up","kind":"follow_up_correlation","observedAt":"2026-07-30T12:00:04Z","followUpTurnId":"turn_2","assistantTurnId":"assistant_2","resultHash":"$RESULT_HASH"}""")

private fun canonicalJson(payload: String): String =
    CanonicalVoiceExperienceJson.encodeObject(Json.parseToJsonElement(payload).jsonObject)

private const val VOICE_SESSION_ID = "lvs_1"
private const val AGENT_IDENTITY = "agent_1"
private const val PERSISTED_AT = "2026-07-30T12:00:05Z"
private const val REQUEST_HASH =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
private const val ARGUMENT_HASH =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"
private const val OWNER_HASH =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
private const val CONVERSATION_HASH =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"
private val VOICE_SESSION_HASH = voiceSha256(VOICE_SESSION_ID)
private const val ROOM_HASH =
    "sha256:3333333333333333333333333333333333333333333333333333333333333333"
private const val TRACE_HASH =
    "sha256:4444444444444444444444444444444444444444444444444444444444444444"
private const val RESULT_HASH =
    "sha256:5ba14662f757c0819a81f38b78c10d7b8cf7dda9ef6014b3ca7c25b5b7711d77"
private const val ANSWER_ONE_HASH =
    "sha256:83331a5e274ed68d54e09fd859e39f92c0e833301485dbef3cfc216f778db5bd"

private fun hash(character: Char): String = "sha256:" + character.toString().repeat(64)
