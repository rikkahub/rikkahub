package me.rerere.rikkahub.data.ai

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLoggingInterceptorRedactionTest {

    @Test
    fun `redactHeaders replaces auth header values but keeps names and benign headers`() {
        val headers = Headers.Builder()
            .add("Authorization", "Bearer oauth-secret")
            .add("x-api-key", "ck-secret")
            .add("x-goog-api-key", "g-secret")
            .add("anthropic-version", "2023-06-01")
            .build()

        val redacted = redactHeaders(headers)

        // No secret value leaks through
        assertFalse(redacted.values.any { it.contains("oauth-secret") })
        assertFalse(redacted.values.any { it.contains("ck-secret") })
        assertFalse(redacted.values.any { it.contains("g-secret") })

        // Secret header NAMES are preserved (you can still see WHICH auth was sent)
        assertEquals(REDACTED, redacted["Authorization"])
        assertEquals(REDACTED, redacted["x-api-key"])
        assertEquals(REDACTED, redacted["x-goog-api-key"])

        // Benign header passes through unchanged
        assertEquals("2023-06-01", redacted["anthropic-version"])
    }

    @Test
    fun `redactUrlSecrets strips key query param but preserves path host and other params`() {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini:streamGenerateContent?alt=sse&key=g-secret"

        val redacted = redactUrlSecrets(url)

        assertFalse(redacted.contains("g-secret"))
        assertTrue(redacted.contains("alt=sse"))
        assertTrue(redacted.contains("generativelanguage.googleapis.com"))
        assertTrue(redacted.contains("/v1beta/models/gemini:streamGenerateContent"))
    }

    @Test
    fun `bodyMetadataForLog stores no raw prompt document or secret content`() {
        // An AI-shaped request body carrying a raw user prompt, document text, a base64
        // image payload, and a secret nested inside an object (which key-based JSON
        // redaction would miss). AC #1: none of this content may reach the log buffer.
        val body = """
            {
              "model": "gpt-5",
              "messages": [
                {"role": "user", "content": "SECRET-PROMPT-please-summarize-my-diary"},
                {"role": "user", "content": "DOCUMENT-TEXT-confidential-contract-clause"}
              ],
              "image": "data:image/png;base64,BASE64-IMAGE-PAYLOAD-AAAA",
              "api_key": {"value": "sk-NESTED-LEAK"},
              "token": ["sk-ARRAY-LEAK"]
            }
        """.trimIndent()

        // The interceptor only has byte size + content type, never the body string.
        val meta = bodyMetadataForLog(byteCount = body.toByteArray().size.toLong(), contentType = "application/json")

        // No raw prompt, document, base64, or nested/array secret survives.
        listOf(
            "SECRET-PROMPT", "DOCUMENT-TEXT", "BASE64-IMAGE-PAYLOAD",
            "sk-NESTED-LEAK", "sk-ARRAY-LEAK", "summarize-my-diary",
        ).forEach { sentinel ->
            assertFalse("Raw content leaked: $sentinel", meta.contains(sentinel))
        }

        // Safe metadata only: a redacted placeholder plus size + content type for debugging.
        assertTrue(meta.contains("redacted"))
        assertTrue(meta.contains(body.toByteArray().size.toString()))
        assertTrue(meta.contains("application/json"))
    }

    @Test
    fun `bodyMetadataForLog reports size and falls back to unknown content type`() {
        val meta = bodyMetadataForLog(byteCount = 1234L, contentType = null)

        assertTrue(meta.contains("1234"))
        assertTrue(meta.contains("unknown"))
    }

    @Test
    fun `bodyMetadataForLog reports unknown bytes when length is unavailable`() {
        // A streamed (unknown-length) body must still produce metadata WITHOUT reading the
        // body to measure it; size degrades to "unknown bytes", content-type is preserved.
        val meta = bodyMetadataForLog(byteCount = null, contentType = "application/json")

        assertTrue(meta.contains("unknown bytes"))
        assertTrue(meta.contains("application/json"))
    }
}
