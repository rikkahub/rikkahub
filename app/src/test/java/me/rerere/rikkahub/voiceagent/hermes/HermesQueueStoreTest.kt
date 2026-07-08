package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.FakeVoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesQueueStoreTest {
    private val writer = HermesToolRecordWriter()
    private val transcriptPersister = VoiceTranscriptPersister()

    @Test
    fun `visible message uses the finished template for complete records and marks message written`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-1",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val appended = store.appendVisibleResultMessageIfNeeded(callId = "call-1", jobId = "job-1")

        assertTrue(appended)
        val texts = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }
        assertEquals(listOf("Hermes finished: the prompt\n\nthe answer"), texts)
        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-1" }
        assertTrue(record.messageWritten)

        val secondAppend = store.appendVisibleResultMessageIfNeeded(callId = "call-1", jobId = "job-1")

        assertFalse(secondAppend)
        val textsAfterSecondCall = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }
        assertEquals(listOf("Hermes finished: the prompt\n\nthe answer"), textsAfterSecondCall)
    }

    @Test
    fun `visible message uses the could-not-finish template with reason for failed records`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Failed("boom"),
                jobId = "job-1",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val appended = store.appendVisibleResultMessageIfNeeded(callId = "call-1", jobId = "job-1")

        assertTrue(appended)
        val texts = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }
        assertEquals(listOf("Hermes could not finish: the prompt (boom)"), texts)
    }

    @Test
    fun `latestRecord matches identity including null jobId`() = runTest {
        // The writer merges a null-jobId record into a later jobId-bearing upsert
        // (jobId adoption), so coexisting records for the same call are hand-built:
        // (jobId=null, Pending) alongside (jobId=job-1, Running).
        val initialConversation = conversationOf(
            hermesToolPart(callId = "call-1", jobId = null, status = HermesQueueStatus.Pending),
            hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Running),
        )
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val pendingRecord = store.latestRecord(callId = "call-1", jobId = null)
        assertNotNull(pendingRecord)
        assertEquals(null, pendingRecord!!.jobId)
        assertEquals(HermesQueueStatus.Pending, pendingRecord.status)
        assertEquals("job-1", store.latestRecord(callId = "call-1", jobId = "job-1")?.jobId)
        assertNull(store.latestRecord(callId = "call-1", jobId = "job-2"))
        assertNull(store.latestRecord(callId = "missing", jobId = null))
    }

    @Test
    fun `activeRecords keeps latest per identity and drops terminal`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-1",
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-2",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-2",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val active = store.activeRecords()
        assertEquals(listOf("call-1"), active.map { it.callId })
    }

    @Test
    fun `unannouncedTerminalRecords drops announced and non-terminal`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-1",
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-2",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-2",
                announceOnWrite = true,
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-3",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-3",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val unannounced = store.unannouncedTerminalRecords()
        assertEquals(listOf("call-1"), unannounced.map { it.callId })
    }

    @Test
    fun `latestCancelCandidate honors requireUnsubmitted`() = runTest {
        // Coexisting records for call-1 (hand-built; the writer would merge them):
        // an unsubmitted (jobId=null, Pending) record and a later (jobId=job-1, Running) one.
        val initialConversation = conversationOf(
            hermesToolPart(callId = "call-1", jobId = null, status = HermesQueueStatus.Pending),
            hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Running),
        )
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        assertEquals(
            "job-1",
            store.latestCancelCandidate(callId = "call-1", requireUnsubmitted = false)?.jobId,
        )
        val unsubmitted = store.latestCancelCandidate(callId = "call-1", requireUnsubmitted = true)
        assertNotNull(unsubmitted)
        assertEquals(null, unsubmitted!!.jobId)
        assertEquals(HermesQueueStatus.Pending, unsubmitted.status)
    }

    private fun conversationOf(vararg tools: UIMessagePart.Tool): Conversation =
        Conversation.ofId(Uuid.random()).updateCurrentMessages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList()))
        )

    /** Hand-builds an ask_hermes tool part so records the writer would merge can coexist. */
    private fun hermesToolPart(
        callId: String,
        jobId: String?,
        status: HermesQueueStatus,
    ): UIMessagePart.Tool {
        val record = HermesQueueRecord(
            callId = callId,
            jobId = jobId,
            prompt = "the prompt",
            status = status,
            answer = null,
            error = null,
            announcement = HermesAnnouncementState.NotAnnounced,
            createdAt = "2026-07-08T00:00:00Z",
            updatedAt = "2026-07-08T00:00:01Z",
        )
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = VoiceAgentToolNames.ASK_HERMES,
            input = """{"prompt":"the prompt"}""",
            output = emptyList(),
            metadata = record.toMetadata(nowIso = "2026-07-08T00:00:01Z"),
        )
    }
}
