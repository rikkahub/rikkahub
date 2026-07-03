package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueSnapshot
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceHermesQueueUiStatusTest {
    private val persister = VoiceConversationPersister()

    @Test
    fun `from snapshot exposes compact queue counts for UI state`() {
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "queued",
                    prompt = "queued request",
                    status = VoiceToolRecordStatus.Queued,
                    jobId = "job-queued",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "running",
                    prompt = "running request",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-running",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "complete",
                    prompt = "complete request",
                    status = VoiceToolRecordStatus.Complete("complete answer"),
                    jobId = "job-complete",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "failed",
                    prompt = "failed request",
                    status = VoiceToolRecordStatus.Failed("failed reason"),
                    jobId = "job-failed",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "expired",
                    prompt = "expired request",
                    status = VoiceToolRecordStatus.Expired("expired reason"),
                    jobId = "job-expired",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "canceled",
                    prompt = "canceled request",
                    status = VoiceToolRecordStatus.Canceled("canceled reason"),
                    jobId = "job-canceled",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "announced",
                    prompt = "announced request",
                    status = VoiceToolRecordStatus.Complete("announced answer"),
                    jobId = "job-announced",
                    resultAnnounced = true,
                )
            }

        val status = VoiceHermesQueueUiStatus.fromSnapshot(HermesQueueSnapshot.from(conversation))

        assertEquals(2, status.activeCount)
        assertEquals(1, status.completedWaitingCount)
        assertEquals(1, status.failedWaitingCount)
        assertEquals(1, status.expiredWaitingCount)
        assertEquals(1, status.canceledWaitingCount)
        assertEquals(1, status.announcedTerminalCount)
        assertTrue(status.hasVisibleWork)
    }

    @Test
    fun `announced terminal records are counted without visible work`() {
        val conversation = persister.upsertHermesTool(
            conversation = Conversation.ofId(Uuid.random()),
            callId = "announced",
            prompt = "announced request",
            status = VoiceToolRecordStatus.Complete("announced answer"),
            jobId = "job-announced",
            resultAnnounced = true,
        )

        val status = VoiceHermesQueueUiStatus.fromSnapshot(HermesQueueSnapshot.from(conversation))

        assertEquals(0, status.activeCount)
        assertEquals(0, status.completedWaitingCount)
        assertEquals(0, status.failedWaitingCount)
        assertEquals(0, status.expiredWaitingCount)
        assertEquals(0, status.canceledWaitingCount)
        assertEquals(1, status.announcedTerminalCount)
        assertFalse(status.hasVisibleWork)
    }
}
