package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.livekit.LiveKitExperimentalVoiceCallException
import me.rerere.rikkahub.voiceagent.livekit.LiveKitSessionFailureCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallServicePolicyTest {
    @Test
    fun `log detail redacts credentials`() {
        val detail = IllegalStateException(
            "Bearer abc.def token=private api_key=also-private harmless=value",
        ).toVoiceAgentLogDetail()

        assertFalse(detail.contains("abc.def"))
        assertFalse(detail.contains("private"))
        assertTrue(detail.contains("Bearer [redacted]"))
        assertTrue(detail.contains("token=[redacted]"))
        assertTrue(detail.contains("api_key=[redacted]"))
        assertTrue(detail.contains("harmless=value"))
    }

    @Test
    fun `log detail is bounded`() {
        val detail = IllegalStateException("x".repeat(2_000)).toVoiceAgentLogDetail()

        assertEquals(512, detail.length)
    }

    @Test
    fun `LiveKit service failure log contains only its fixed category`() {
        val privateDetail = "private upstream payload"
        val error = LiveKitExperimentalVoiceCallException(
            message = "request failed $privateDetail",
            cause = IllegalStateException(privateDetail),
            failureCategory = LiveKitSessionFailureCategory.HttpServerFailure,
        )

        val message = error.toVoiceAgentServiceLogMessage()

        assertEquals("service operation failed category=http_server_failure", message)
        assertFalse(message.contains(privateDetail))
    }
}
