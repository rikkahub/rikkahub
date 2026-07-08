package me.rerere.rikkahub.voiceagent.persistence

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.hermes.HermesAnnouncementState
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueRecord
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueSnapshot
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesQueuePromptSummaryTest {

    private fun record(
        status: HermesQueueStatus = HermesQueueStatus.Running,
        announcement: HermesAnnouncementState = HermesAnnouncementState.NotAnnounced,
        jobId: String? = "job-1",
        answer: String? = if (status == HermesQueueStatus.Complete) "the answer" else null,
        error: String? = if (status.isTerminal && status != HermesQueueStatus.Complete) "boom" else null,
    ) = HermesQueueRecord(
        callId = "call-1",
        jobId = jobId,
        prompt = "the prompt",
        status = status,
        answer = answer,
        error = error,
        announcement = announcement,
        createdAt = "2026-07-08T00:00:00Z",
        updatedAt = "2026-07-08T00:00:01Z",
    )

    private fun HermesQueueRecord.toToolPart(): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = VoiceAgentToolNames.ASK_HERMES,
        input = """{"prompt":"$prompt"}""",
        output = listOfNotNull(
            (answer ?: error)?.let { UIMessagePart.Text(it, metadata = null) }
        ),
        metadata = toMetadata(nowIso = updatedAt ?: "2026-07-08T00:00:01Z"),
    )

    private fun conversationOf(vararg tools: UIMessagePart.Tool): Conversation {
        return Conversation.ofId(Uuid.random())
            .updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = tools.toList(),
                    )
                )
            )
    }

    @Test
    fun `status question summary exposes active prompts and terminal counts without terminal content`() {
        // Ported from old row 46, assertions kept verbatim.
        val active = record(status = HermesQueueStatus.Running, jobId = "job-active")
            .copy(callId = "active", prompt = "active request").toToolPart()
        val complete = record(
            status = HermesQueueStatus.Complete,
            jobId = "job-complete",
            answer = "complete private answer",
        ).copy(callId = "complete", prompt = "complete private request").toToolPart()
        val failed = record(
            status = HermesQueueStatus.Failed,
            jobId = "job-failed",
            error = "failed private reason",
        ).copy(callId = "failed", prompt = "failed private request").toToolPart()
        val expired = record(
            status = HermesQueueStatus.Expired,
            jobId = "job-expired",
            error = "expired private reason",
        ).copy(callId = "expired", prompt = "expired private request").toToolPart()
        val canceled = record(
            status = HermesQueueStatus.Canceled,
            jobId = "job-canceled",
            error = "canceled private reason",
        ).copy(callId = "canceled", prompt = "canceled private request").toToolPart()

        val summary = HermesQueueSnapshot.from(conversationOf(active, complete, failed, expired, canceled))
            .toStatusQuestionPromptSummary()

        assertTrue(summary.contains("Durable Hermes queue status:"))
        assertTrue(summary.contains("- Still running: active request"))
        assertTrue(summary.contains("- Unannounced terminal results: completed=1, failed=1, expired=1, canceled=1"))
        assertTrue(summary.contains("answer only from this durable queue status"))
        assertFalse(summary.contains("complete private request"))
        assertFalse(summary.contains("complete private answer"))
        assertFalse(summary.contains("failed private request"))
        assertFalse(summary.contains("failed private reason"))
        assertFalse(summary.contains("expired private request"))
        assertFalse(summary.contains("expired private reason"))
        assertFalse(summary.contains("canceled private request"))
        assertFalse(summary.contains("canceled private reason"))
    }

    @Test
    fun `status question summary is empty for announced terminal records only`() {
        // Ported from old row 47.
        val announced = record(
            status = HermesQueueStatus.Complete,
            jobId = "job-announced",
            announcement = HermesAnnouncementState.Announced,
            answer = "announced private answer",
        ).copy(callId = "announced", prompt = "announced private request").toToolPart()

        val summary = HermesQueueSnapshot.from(conversationOf(announced)).toStatusQuestionPromptSummary()

        assertEquals("", summary)
    }
}
