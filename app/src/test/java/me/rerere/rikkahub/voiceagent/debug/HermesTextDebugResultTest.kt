package me.rerere.rikkahub.voiceagent.debug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HermesTextDebugResultTest {
    @Test
    fun `success contains only exact result and sanitized HTTP evidence`() {
        val line = HermesTextDebugResult.Success(
            httpStatus = 200,
            requestOrigin = "https://dev-remote-machine-1.tail83108.ts.net:8642",
        ).toLogLine()

        assertEquals(
            "debug_hermes_text result=success exact=true http_status=200 " +
                "request_origin=https://dev-remote-machine-1.tail83108.ts.net:8642",
            line,
        )
        assertFalse(line.contains("/v1/"))
        assertFalse(line.contains("Bearer"))
    }

    @Test
    fun `failures expose only a stable safe category`() {
        assertEquals(
            "debug_hermes_text result=failure category=wrong_answer",
            HermesTextDebugResult.Failure(HermesTextDebugFailure.WrongAnswer).toLogLine(),
        )
        assertEquals(
            "debug_hermes_text result=failure category=timeout",
            HermesTextDebugResult.Failure(HermesTextDebugFailure.Timeout).toLogLine(),
        )
    }

    @Test
    fun `success rejects a full request URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            HermesTextDebugResult.Success(
                httpStatus = 200,
                requestOrigin = "https://dev-remote-machine-1.tail83108.ts.net:8642/v1/chat/completions?key=secret",
            )
        }
    }
}
