package me.rerere.rikkahub.voiceagent.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class HermesTelemetryLogSanitizerTest {
    @Test
    fun `failure message keeps only safe Hermes Voice status prefix`() {
        val sanitized = HermesTelemetryLogSanitizer.failureMessage(
            """Hermes Voice request failed 403: {"prompt":"private prompt","answer":"private answer"}"""
        )

        assertEquals("Hermes Voice request failed 403", sanitized)
    }

    @Test
    fun `failure message falls back to bounded prefix`() {
        val sanitized = HermesTelemetryLogSanitizer.failureMessage(
            "provider failed: private prompt detail that should not keep the suffix"
        )

        assertEquals("provider failed", sanitized)
    }
}
