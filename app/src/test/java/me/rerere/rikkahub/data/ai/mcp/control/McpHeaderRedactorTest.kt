package me.rerere.rikkahub.data.ai.mcp.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHeaderRedactorTest {

    @Test fun `Authorization header is redacted to last 4 chars`() {
        assertEquals("…wxyz", McpHeaderRedactor.redactHeaderValue("Authorization", "Bearer abc.uvwxyz"))
    }

    @Test fun `case-insensitive header name match`() {
        assertEquals("…1234", McpHeaderRedactor.redactHeaderValue("AUTHORIZATION", "Bearer abc1234"))
        assertEquals("…1234", McpHeaderRedactor.redactHeaderValue("authorization", "Bearer abc1234"))
        assertEquals("…1234", McpHeaderRedactor.redactHeaderValue("AuThOrIzAtIoN", "Bearer abc1234"))
    }

    @Test fun `non-sensitive headers pass through unchanged`() {
        assertEquals("application/json", McpHeaderRedactor.redactHeaderValue("Content-Type", "application/json"))
        assertEquals("text/plain", McpHeaderRedactor.redactHeaderValue("Accept", "text/plain"))
    }

    @Test fun `short sensitive value redacts to ellipsis only`() {
        assertEquals("…", McpHeaderRedactor.redactHeaderValue("X-Api-Key", "abc"))
        assertEquals("…", McpHeaderRedactor.redactHeaderValue("X-Api-Key", ""))
    }

    @Test fun `all canonical sensitive header names recognized`() {
        for (name in listOf(
            "Authorization", "Proxy-Authorization", "X-Api-Key", "X-Api-Token",
            "X-Auth-Token", "X-Access-Token", "Cookie", "Set-Cookie", "X-CSRF-Token",
        )) {
            assertTrue("$name should be sensitive", McpHeaderRedactor.isSensitive(name))
        }
        assertFalse(McpHeaderRedactor.isSensitive("Content-Type"))
        assertFalse(McpHeaderRedactor.isSensitive("X-Custom-Header"))
    }

    @Test fun `redactHeaders preserves order and duplicates`() {
        val input = listOf(
            "Authorization" to "Bearer alpha",
            "X-Api-Key" to "key-bravo",
            "X-Api-Key" to "key-charlie",
            "Content-Type" to "application/json",
        )
        val redacted = McpHeaderRedactor.redactHeaders(input)
        assertEquals(4, redacted.size)
        assertEquals("Authorization", redacted[0].first)
        assertEquals("…lpha", redacted[0].second)
        assertEquals("…ravo", redacted[1].second)
        assertEquals("…rlie", redacted[2].second)
        assertEquals("application/json", redacted[3].second)
    }

    @Test fun `classify counts sensitive vs plain`() {
        val (s, p) = McpHeaderRedactor.classify(
            listOf(
                "Authorization" to "Bearer x",
                "X-Api-Key" to "y",
                "Content-Type" to "application/json",
            )
        )
        assertEquals(2, s)
        assertEquals(1, p)
    }

    @Test fun `whitespace around header name is tolerated`() {
        assertTrue(McpHeaderRedactor.isSensitive("  Authorization  "))
    }
}
