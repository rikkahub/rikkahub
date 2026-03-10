package me.rerere.rikkahub.data.ai.tools.termux

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPtySessionProtocolTest {
    @Test
    fun `toToolResponse should preserve session metadata`() {
        val response = TermuxPtyServerResponse(
            output = "hello",
            sessionId = "session-123",
            running = true,
            exitCode = null,
            error = null,
            truncated = true,
        )

        val toolResponse = response.toToolResponse()

        assertEquals("hello", toolResponse.output)
        assertEquals("session-123", toolResponse.sessionId)
        assertEquals(true, toolResponse.running)
        assertEquals(true, toolResponse.truncated)
    }

    @Test
    fun `toToolResponse should normalize interactive terminal output`() {
        val response = TermuxPtyServerResponse(
            output = "\u001B[1;35m>>> \u001B[0mprint('x')\r\nx\r\n",
            sessionId = "session-123",
            running = true,
        )

        val toolResponse = response.toToolResponse()

        assertEquals(">>> print('x')\nx\n", toolResponse.output)
    }

    @Test
    fun `encode should use snake case fields`() {
        val payload = TermuxPtyToolResponse(
            output = "chunk",
            sessionId = "session-456",
            running = false,
            exitCode = 130,
            error = "interrupted",
            truncated = false,
        ).encode(JsonInstant)

        assertTrue(payload.contains("\"session_id\":\"session-456\""))
        assertTrue(payload.contains("\"exit_code\":130"))
        assertTrue(payload.contains("\"output\":\"chunk\""))
    }
}
