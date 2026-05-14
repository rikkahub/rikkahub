package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.telegram.TelegramApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Locks in the token-verification error classification used by telegram_set_token.
 *
 * The distinction is load-bearing: a HTTP 401 means the token is permanently invalid and
 * the LLM must NOT retry it (it has to fetch a fresh token from @BotFather); anything else
 * is transient and a retry is reasonable. Before this split both failures produced the same
 * opaque envelope and the model would retry a dead token forever.
 */
class TelegramTokenErrorClassificationTest {

    @Test
    fun `telegram 401 maps to token_invalid with do-not-retry recovery`() {
        val env = classifyTokenVerifyError(TelegramApiException(401, "Unauthorized"))
        assertEquals("token_invalid", env["error"]?.jsonPrimitive?.content)
        assertTrue(
            "detail mentions 401",
            env["detail"]?.jsonPrimitive?.content?.contains("401") == true
        )
        assertTrue(
            "recovery tells the model not to retry",
            env["recovery"]?.jsonPrimitive?.content?.contains("do NOT retry", ignoreCase = true) == true
        )
    }

    @Test
    fun `socket timeout maps to network_error with retry recovery`() {
        val env = classifyTokenVerifyError(SocketTimeoutException("timeout"))
        assertEquals("network_error", env["error"]?.jsonPrimitive?.content)
        assertTrue(
            "recovery suggests retry",
            env["recovery"]?.jsonPrimitive?.content?.contains("retry", ignoreCase = true) == true
        )
    }

    @Test
    fun `unknown host maps to network_error`() {
        val env = classifyTokenVerifyError(UnknownHostException("api.telegram.org"))
        assertEquals("network_error", env["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generic IOException maps to network_error`() {
        val env = classifyTokenVerifyError(IOException("connection reset"))
        assertEquals("network_error", env["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non-401 telegram api error maps to network_error not token_invalid`() {
        // A 500 / 429 from Telegram is a server-side or rate-limit problem, not a bad token.
        val env = classifyTokenVerifyError(TelegramApiException(429, "Too Many Requests"))
        assertEquals("network_error", env["error"]?.jsonPrimitive?.content)
        assertTrue(
            "detail carries the api error code",
            env["detail"]?.jsonPrimitive?.content?.contains("429") == true
        )
    }

    @Test
    fun `every envelope carries error detail and recovery keys`() {
        listOf(
            classifyTokenVerifyError(TelegramApiException(401, "Unauthorized")),
            classifyTokenVerifyError(IOException("boom")),
        ).forEach { env ->
            assertTrue("has error", env.containsKey("error"))
            assertTrue("has detail", env.containsKey("detail"))
            assertTrue("has recovery", env.containsKey("recovery"))
        }
    }
}
