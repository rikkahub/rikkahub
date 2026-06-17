package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the non-streaming error surface (audit Q6, PR #266).
 *
 * Bug: ClaudeProvider.generateText and ChatCompletionsAPI.generateText threw a bare
 * `Exception("Failed to get response: <code> <raw body>")` for any non-2xx response, while every
 * STREAMING failure routes the same wire format through parseErrorDetail into a typed
 * HttpException. Title/suggestion/compress/translation — all non-streaming consumers — surfaced a
 * raw JSON dump instead of the parsed provider message: two error surfaces for one wire format,
 * with the user-facing one being the worse copy.
 *
 * [parseHttpErrorBody] is the shared helper both providers now throw from: a JSON body yields the
 * parseErrorDetail message (parity with streaming onFailure), a non-JSON body (HTML proxy page)
 * falls back to the previous code+body shape so no information is lost.
 */
class ParseHttpErrorBodyTest {

    @Test
    fun `a json error body resolves to the parsed provider message`() {
        val body = """{"error":{"message":"rate limit exceeded","type":"rate_limit_error"}}"""

        val e = parseHttpErrorBody(429, body)

        assertEquals("rate limit exceeded", e.message)
    }

    @Test
    fun `a non-json body falls back to the raw code plus body shape`() {
        val body = "<html>502 Bad Gateway</html>"

        val e = parseHttpErrorBody(502, body)

        assertTrue("fallback must carry the status code", e.message!!.contains("502"))
        assertTrue("fallback must carry the raw body", e.message!!.contains(body))
    }

    @Test
    fun `an oversized non-json body is bounded, not copied verbatim`() {
        // A long HTML/proxy body parses to a bare string primitive (parseErrorDetail's
        // JsonPrimitive leaf) — the verbatim-copy path the audit flagged. It must be capped.
        val body = "<html>" + "A".repeat(50_000) + "</html>"

        val e = parseHttpErrorBody(502, body)

        assertTrue("must be bounded well under the body size", e.message!!.length < body.length)
        assertTrue("must mark truncation", e.message!!.contains("truncated"))
        assertTrue("cap is ~MAX_ERROR_BODY_CHARS", e.message!!.length <= MAX_ERROR_BODY_CHARS + 64)
    }

    @Test
    fun `an oversized json object with no error field is bounded`() {
        // No recognized error field → whole object is serialized into the message; bound it too.
        val big = buildString {
            append("{")
            append((0 until 4000).joinToString(",") { "\"k$it\":\"v$it\"" })
            append("}")
        }

        val e = parseHttpErrorBody(500, big)

        assertTrue("serialized object must be bounded", e.message!!.length <= MAX_ERROR_BODY_CHARS + 64)
        assertTrue("must mark truncation", e.message!!.contains("truncated"))
    }

    @Test
    fun `a short parsed provider message is left intact`() {
        val body = """{"error":{"message":"rate limit exceeded"}}"""
        assertEquals("rate limit exceeded", parseHttpErrorBody(429, body).message)
    }

    @Test
    fun `a body at or under the cap is unchanged`() {
        val body = "x".repeat(MAX_ERROR_BODY_CHARS)
        assertEquals(body, body.truncateForErrorMessage())
    }
}
