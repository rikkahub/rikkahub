package me.rerere.rikkahub.voiceagent

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
}
