package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceToolStatusProjectorTest {
    @Test
    fun `calling status wins over queued and failed`() {
        val status = summarizeVoiceToolStatus(
            toolCalls = mapOf(
                "queued" to VoiceToolStatus.QueuedHermes(callId = "queued", jobId = "job-1"),
                "calling" to VoiceToolStatus.CallingHermes(callId = "calling", elapsedMs = 12),
                "failed" to VoiceToolStatus.HermesFailed(callId = "failed", message = "failed"),
            ),
            fallback = VoiceToolStatus.Idle,
        )

        assertEquals(VoiceToolStatus.CallingHermes(callId = "calling", elapsedMs = 12), status)
    }

    @Test
    fun `fallback active status wins when it is active`() {
        val fallback = VoiceToolStatus.QueuedHermes(callId = "new", jobId = "job-2")

        assertEquals(
            fallback,
            summarizeVoiceToolStatus(toolCalls = emptyMap(), fallback = fallback),
        )
    }

    @Test
    fun `idle wins when no calls exist`() {
        assertEquals(
            VoiceToolStatus.Idle,
            summarizeVoiceToolStatus(toolCalls = emptyMap(), fallback = VoiceToolStatus.Idle),
        )
    }
}
