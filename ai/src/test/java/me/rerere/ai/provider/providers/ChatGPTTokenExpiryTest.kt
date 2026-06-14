package me.rerere.ai.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Regression test for the paste-only ChatGPT token-expiry invariant (issue #285):
 * a known-expired pasted JWT must be detected BEFORE any network call so the provider can surface
 * a clear "token expired — paste a new one" error instead of a raw 401; an unparseable paste must
 * never crash and must defer to the server's own auth error.
 *
 * Pure JVM unit test: no OkHttp, no Android, no network.
 */
@OptIn(ExperimentalEncodingApi::class)
class ChatGPTTokenExpiryTest {

    // header.payload.signature where payload = base64url(JSON), padding stripped like a real JWT.
    private fun jwt(payloadJson: String): String {
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val payload = encode(payloadJson)
        return "$header.$payload.sig"
    }

    private fun encode(s: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(s.encodeToByteArray())

    private val now = 1_700_000_000L

    @Test
    fun `expired exp is detected as expired`() {
        val token = jwt("""{"exp":${now - 10}}""")
        assertEquals(now - 10, parseJwtExp(token))
        assertTrue(isChatGptTokenExpired(token, now))
    }

    @Test
    fun `exp exactly at now is expired`() {
        val token = jwt("""{"exp":$now}""")
        assertTrue(isChatGptTokenExpired(token, now))
    }

    @Test
    fun `future exp is not expired`() {
        val token = jwt("""{"exp":${now + 3600}}""")
        assertEquals(now + 3600, parseJwtExp(token))
        assertFalse(isChatGptTokenExpired(token, now))
    }

    @Test
    fun `jwt without exp claim is not expired`() {
        val token = jwt("""{"sub":"user-123"}""")
        assertNull(parseJwtExp(token))
        assertFalse(isChatGptTokenExpired(token, now))
    }

    @Test
    fun `non-jwt paste yields null exp and is not expired`() {
        val token = "not-a-jwt-just-some-opaque-string"
        assertNull(parseJwtExp(token))
        assertFalse(isChatGptTokenExpired(token, now))
    }

    @Test
    fun `malformed middle segment yields null exp and is not expired`() {
        val token = "header.@@@not-base64@@@.sig"
        assertNull(parseJwtExp(token))
        assertFalse(isChatGptTokenExpired(token, now))
    }

    @Test
    fun `empty token is not expired`() {
        assertNull(parseJwtExp(""))
        assertFalse(isChatGptTokenExpired("", now))
    }
}
