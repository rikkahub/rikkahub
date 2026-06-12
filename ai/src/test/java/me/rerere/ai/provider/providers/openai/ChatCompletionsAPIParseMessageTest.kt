package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Regression test for the streaming-path parse hazard in ChatCompletionsAPI.parseMessage
 * (audit Q5, PR #266).
 *
 * Bug: `require(url.startsWith("data:image"))` threw for any non-data image URL. parseMessage
 * runs inside the SSE EventSourceListener.onEvent forEach, and throwing out of onEvent is
 * mishandled by OkHttp's SSE loop (the exact hazard the adjacent #241 comment documents) — a
 * proxy emitting an http/content image URL killed the stream through the broken channel.
 *
 * Fix: an unsupported image URL is SKIPPED, preserving the rest of the frame — the same policy
 * this parser already applies to unknown tool-call types and unknown annotation types.
 */
class ChatCompletionsAPIParseMessageTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun invokeParseMessage(message: JsonObject): UIMessage {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "parseMessage",
            JsonObject::class.java
        )
        method.isAccessible = true
        try {
            return method.invoke(api, message) as UIMessage
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun messageWithImageUrl(url: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", "hello")
        put("images", buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", url) })
            })
        })
    }

    @Test
    fun `a non-data image url is skipped instead of crashing the frame parse`() {
        val message = invokeParseMessage(messageWithImageUrl("https://example.com/image.png"))

        assertTrue(
            "unsupported image URL must be skipped, not crash; rest of the frame must survive",
            message.parts.filterIsInstance<UIMessagePart.Image>().isEmpty()
        )
        assertEquals(
            "the text content of the same frame must be preserved",
            listOf("hello"),
            message.parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        )
    }

    @Test
    fun `a data-uri image is still parsed into an Image part`() {
        val message = invokeParseMessage(messageWithImageUrl("data:image/png;base64,QUJD"))

        assertEquals(
            listOf("QUJD"),
            message.parts.filterIsInstance<UIMessagePart.Image>().map { it.url }
        )
    }
}
