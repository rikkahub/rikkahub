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
}
