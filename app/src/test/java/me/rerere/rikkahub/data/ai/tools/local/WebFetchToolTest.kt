package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers web_fetch's input-validation paths, all of which early-return before any network
 * call — so a default [OkHttpClient] is never actually used. Real request/response behavior
 * is exercised by instrumented tests / live runs.
 */
class WebFetchToolTest {

    private val tool: Tool = webFetchTool(OkHttpClient())

    private fun invoke(args: String): JsonObject {
        val text = runBlocking {
            (tool.execute(Json.parseToJsonElement(args)) as List<*>)
                .filterIsInstance<UIMessagePart.Text>()
                .first().text
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.error() = this["error"]?.jsonPrimitive?.content

    @Test fun `missing url is rejected`() {
        assertEquals("missing_url", invoke("""{}""").error())
    }

    @Test fun `blank url is rejected`() {
        assertEquals("missing_url", invoke("""{"url":"   "}""").error())
    }

    @Test fun `non-http url is rejected`() {
        assertEquals("bad_url", invoke("""{"url":"ftp://example.com/x"}""").error())
    }

    @Test fun `file url is rejected`() {
        assertEquals("bad_url", invoke("""{"url":"file:///etc/passwd"}""").error())
    }

    @Test fun `unsupported method is rejected`() {
        assertEquals(
            "bad_method",
            invoke("""{"url":"https://example.com","method":"DELETE"}""").error(),
        )
    }

    @Test fun `method is case-insensitive and clears validation`() {
        // "get" normalises to GET and passes validation — it then proceeds to the network
        // layer, which against an unroutable address yields network_error or timeout, never
        // a validation error. We only assert it cleared the validation gate.
        val err = invoke("""{"url":"http://127.0.0.1:9","method":"get"}""").error()
        assertEquals(true, err == "network_error" || err == "timeout")
    }

    @Test fun `malformed url is rejected as bad_request`() {
        // Passes the http(s)-prefix check but is not a valid URL — caught at request build.
        assertEquals("bad_request", invoke("""{"url":"http://"}""").error())
    }

    // readBounded must never buffer more than cap+1 bytes, and must flag overflow.
    @Test fun `readBounded returns all bytes under cap without truncation`() {
        val (bytes, truncated) = readBounded("abc".byteInputStream(), 8192)
        assertEquals(3, bytes.size)
        assertEquals(false, truncated)
    }

    @Test fun `readBounded at exactly cap is not truncated`() {
        val cap = 256
        val (bytes, truncated) = readBounded(ByteArray(cap).inputStream(), cap)
        assertEquals(cap, bytes.size)
        assertEquals(false, truncated)
    }

    @Test fun `readBounded over cap stops at cap plus one and flags truncated`() {
        val cap = 256
        val (bytes, truncated) = readBounded(ByteArray(cap + 100).inputStream(), cap)
        assertEquals(cap + 1, bytes.size)
        assertEquals(true, truncated)
    }
}
