package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.FakeVoiceConversationStore
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Pending,
                jobId = null,
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-1",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        assertEquals(null, store.latestRecord(callId = "call-1", jobId = "job-2")?.jobId)
        assertEquals("job-1", store.latestRecord(callId = "call-1", jobId = "job-1")?.jobId)
        assertEquals(null, store.latestRecord(callId = "missing", jobId = null))
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
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Pending,
                jobId = null,
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-1",
            )
        }
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
        assertEquals(
            null,
            store.latestCancelCandidate(callId = "call-1", requireUnsubmitted = true)?.jobId,
        )
    }
}
