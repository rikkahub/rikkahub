package me.rerere.rikkahub.voiceagent.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HermesTelemetryLogSanitizerTest {
    @Test
    fun `failure message keeps only safe Voice Lab status prefix`() {
        val sanitized = HermesTelemetryLogSanitizer.failureMessage(
            """Voice Lab request failed 403: {"prompt":"private prompt","answer":"private answer"}"""
        )

        assertEquals("Voice Lab request failed 403", sanitized)
    }

    @Test
    fun `failure message falls back to bounded prefix`() {
        val sanitized = HermesTelemetryLogSanitizer.failureMessage(
            "provider failed: private prompt detail that should not keep the suffix"
        )

        assertEquals("provider failed", sanitized)
    }

    @Test
    fun `queue event detail keeps only routing metadata`() {
        val detail = HermesTelemetryLogSanitizer.queueEventDetail(
            """{"type":"job_completed","callId":"call-1","jobId":"job-1","status":"succeeded","sent":true,"answer":"private"}"""
        )

        assertEquals("type=job_completed callId=call-1 jobId=job-1 status=succeeded sent=true", detail)
        assertFalse(detail.contains("private"))
    }
}
