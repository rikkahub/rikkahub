package me.rerere.common.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression guard for issue #99 (finding: decode-failure messages leak body content).
// Search/TTS decode-failure paths previously interpolated `throwable.message`. With
// kotlinx-serialization 1.11.0 that message embeds a snippet of the offending JSON
// input, so the raw response body — the search query, result snippets, or TTS audio
// payload it failed to parse — re-leaked into logs. redactDecodeError must surface the
// exception class only, never the input snippet.
class RedactDecodeErrorTest {

    @Serializable
    private data class Expected(val ok: Boolean)

    private fun decodeException(badInput: String): Throwable =
        runCatching { Json.decodeFromString<Expected>(badInput) }.exceptionOrNull()
            ?: error("expected decode to fail for input: $badInput")

    @Test
    fun `decode error message embeds the offending input snippet`() {
        // Establishes WHY the leak existed: the throwable's own message carries the
        // input. If kotlinx-serialization ever stops doing this the redaction is moot,
        // but today the snippet is present — proving `${it.message}` was unsafe.
        val secret = "user-private-search-query-9f3a2b"
        val ex = decodeException("""{"ok":"$secret"}""")
        assertTrue(
            "precondition: kotlinx-serialization message embeds the input snippet",
            (ex.message ?: "").contains(secret),
        )
    }

    @Test
    fun `redactDecodeError never echoes the offending input`() {
        val secret = "user-private-search-query-9f3a2b"
        val ex = decodeException("""{"ok":"$secret"}""")

        val redacted = redactDecodeError(ex)

        assertFalse(
            "decode-error redaction must not echo the response body snippet",
            redacted.contains(secret),
        )
        assertFalse(
            "decode-error redaction must not echo the JSON-input marker",
            redacted.contains("JSON input"),
        )
    }

    @Test
    fun `redactDecodeError surfaces the exception class for triage`() {
        val ex = decodeException("not json at all")
        assertEquals(ex::class.simpleName, redactDecodeError(ex))
    }
}
