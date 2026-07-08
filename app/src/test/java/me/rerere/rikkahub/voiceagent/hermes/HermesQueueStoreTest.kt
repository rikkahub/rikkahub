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
}
