package me.rerere.rikkahub.data.ai.tools.termux

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandToolResponseTest {
    @Test
    fun `toToolResponse should preserve failure status`() {
        val payload = TermuxResult(
            stdout = "",
            stderr = "",
            exitCode = 1,
        ).toToolResponse().encode(JsonInstant)

        assertTrue(payload.contains("\"exit_code\":1"))
        assertTrue(payload.contains("\"output\":\"Exit code: 1\""))
        assertFalse(payload.contains("\"success\":"))
        assertFalse(payload.contains("\"error\":"))
    }

    @Test
    fun `toToolResponse should preserve timeout status`() {
        val payload = TermuxResult(
            stdout = "partial output",
            timedOut = true,
            errMsg = "Timed out after 5000ms",
        ).toToolResponse().encode(JsonInstant)

        assertTrue(payload.contains("\"output\":\"partial output\\n"))
        assertTrue(payload.contains("Timed out"))
        assertTrue(payload.contains("Timed out after 5000ms"))
        assertFalse(payload.contains("\"timed_out\":"))
        assertFalse(payload.contains("\"success\":"))
    }

    @Test
    fun `toCommandErrorToolResponse should fold setup errors into output`() {
        val payload = IllegalStateException("Termux unavailable")
            .toCommandErrorToolResponse("Install Termux first")
            .encode(JsonInstant)

        assertTrue(payload.contains("\"output\":\"Termux unavailable"))
        assertTrue(payload.contains("Termux unavailable"))
        assertTrue(payload.contains("Install Termux first"))
        assertFalse(payload.contains("\"error\":"))
        assertFalse(payload.contains("\"success\":"))
    }
}
