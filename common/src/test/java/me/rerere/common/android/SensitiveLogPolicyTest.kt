package me.rerere.common.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression guard for issue #100 (central SensitiveLogPolicy). The crash handler
// previously persisted throwable.stackTraceToString(), whose per-exception toString()
// line embeds the message; a kotlinx-serialization JsonDecodingException's message
// carries the offending JSON input. safeExceptionMessage must surface class identity
// only, never the message text. Also guards that the consolidated members behave
// identically to the existing top-level aliases (behavior-preserving move).
class SensitiveLogPolicyTest {

    @Serializable
    private data class Expected(val ok: Boolean)

    private fun decodeException(badInput: String): Throwable =
        runCatching { Json.decodeFromString<Expected>(badInput) }.exceptionOrNull()
            ?: error("expected decode to fail for input: $badInput")

    @Test
    fun `precondition - decode error message embeds the offending input snippet`() {
        // Establishes WHY persisting raw exception messages is unsafe: the throwable's
        // own message carries the input the parse failed on.
        val secret = "user-private-payload-9f3a2b"
        val ex = decodeException("""{"ok":"$secret"}""")
        assertTrue(
            "precondition: kotlinx-serialization message embeds the input snippet",
            (ex.message ?: "").contains(secret),
        )
    }

    @Test
    fun `safeExceptionMessage never echoes the offending input`() {
        val secret = "user-private-payload-9f3a2b"
        val ex = decodeException("""{"ok":"$secret"}""")

        val safe = SensitiveLogPolicy.safeExceptionMessage(ex)!!

        assertFalse(
            "safeExceptionMessage must not echo the response body snippet",
            safe.contains(secret),
        )
        assertFalse(
            "safeExceptionMessage must not echo the JSON-input marker",
            safe.contains("JSON input"),
        )
        assertTrue(
            "safeExceptionMessage must surface the exception class for triage",
            safe.contains(ex::class.simpleName!!),
        )
    }

    @Test
    fun `safeExceptionMessage strips an arbitrary exception message`() {
        val ex = RuntimeException("failed: api_key=sk-LIVE-deadbeef path=/home/user/secret")

        val safe = SensitiveLogPolicy.safeExceptionMessage(ex)!!

        assertFalse(safe.contains("sk-LIVE-deadbeef"))
        assertFalse(safe.contains("/home/user/secret"))
        assertFalse(safe.contains("api_key"))
        assertEquals(RuntimeException::class.qualifiedName, safe)
    }

    @Test
    fun `safeExceptionMessage returns null for null throwable`() {
        assertNull(SensitiveLogPolicy.safeExceptionMessage(null))
    }

    @Test
    fun `safeExceptionMessage is idempotent and class-identity only`() {
        val ex = IllegalStateException("anything secret here")
        val first = SensitiveLogPolicy.safeExceptionMessage(ex)
        val second = SensitiveLogPolicy.safeExceptionMessage(ex)
        assertEquals(first, second)
        assertEquals(IllegalStateException::class.qualifiedName, first)
    }

    @Test
    fun `redactAndTruncate member matches the top-level alias`() {
        val body = "<Error><BucketName>my-private-bucket</BucketName></Error>"
        assertEquals(redactAndTruncate(body), SensitiveLogPolicy.redactAndTruncate(body))
        assertEquals(redactAndTruncate(null), SensitiveLogPolicy.redactAndTruncate(null))
        assertEquals("<redacted body: ${body.length} chars>", SensitiveLogPolicy.redactAndTruncate(body))
        assertFalse(SensitiveLogPolicy.redactAndTruncate(body).contains("my-private-bucket"))
    }

    @Test
    fun `redactDecodeError member matches the top-level alias`() {
        val ex = decodeException("""{"ok":"leak-me"}""")
        assertEquals(redactDecodeError(ex), SensitiveLogPolicy.redactDecodeError(ex))
        assertFalse(SensitiveLogPolicy.redactDecodeError(ex).contains("leak-me"))
    }
}
