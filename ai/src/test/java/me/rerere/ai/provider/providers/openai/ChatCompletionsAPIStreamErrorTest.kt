package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for the SSE error-frame handling in ChatCompletionsAPI.streamText (issue #241).
 *
 * Bug: an `{"error":{...}}` data frame did `throw error` inside the EventSourceListener.onEvent
 * forEach. Throwing out of onEvent is mishandled by OkHttp's SSE loop; the correct termination
 * channel is `close(error)` (the pattern ClaudeProvider/GoogleProvider/ResponseAPI already use).
 *
 * The error-detection logic is extracted into a pure `resolveStreamError(JsonObject): HttpException?`
 * so it can be unit-tested without driving the OkHttp SSE machinery: a frame carrying an "error"
 * key yields a non-null exception (the call site closes the flow with it), and a normal data frame
 * with no "error" key yields null (so it is NOT mistaken for an error).
 */
class ChatCompletionsAPIStreamErrorTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun invokeResolveStreamError(frame: JsonObject): HttpException? {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "resolveStreamError",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(api, frame) as HttpException?
    }

    @Test
    fun `error frame resolves a non-null exception with non-blank message`() {
        val frame = buildJsonObject {
            put("error", buildJsonObject {
                put("message", "rate limit exceeded")
            })
        }

        val result = invokeResolveStreamError(frame)

        assertNotNull(result)
        assertTrue(result!!.message!!.isNotBlank())
        assertTrue(result.message!!.contains("rate limit"))
    }

    @Test
    fun `frame without an error key resolves null`() {
        // A normal data frame must not be mistaken for an error.
        val frame = buildJsonObject {
            put("id", "chatcmpl-1")
            put("model", "gpt-4o")
        }

        assertNull(invokeResolveStreamError(frame))
    }
}
