package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleThoughtSignaturePartTest {
    private val provider = GoogleProvider(OkHttpClient())

    @Test
    fun `stream signature-only part attaches to the preceding text`() {
        val messages = decodeStream(
            listOf(buildJsonObject { put("text", "Hello") }),
            listOf(buildJsonObject { put("text", " world") }),
            listOf(buildJsonObject { put("thoughtSignature", "sig-1") }),
        )

        val text = messages.last().parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("Hello world", text.text)
        assertEquals("sig-1", text.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals(
            "sig-1",
            replayModelParts(messages).single().jsonObject["thoughtSignature"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `stream signature-only part attaches to open reasoning`() {
        val messages = decodeStream(
            listOf(buildJsonObject {
                put("text", "thinking")
                put("thought", true)
            }),
            listOf(buildJsonObject { put("thoughtSignature", "sig-r") }),
            listOf(buildJsonObject { put("text", "answer") }),
        )

        val parts = messages.last().parts
        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("sig-r", reasoning.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        val text = parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("answer", text.text)
        assertEquals(null, text.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    @Test
    fun `stream signature-only part does not overwrite an existing signature`() {
        val messages = decodeStream(
            listOf(buildJsonObject {
                put("text", "Hello")
                put("thoughtSignature", "sig-1")
            }),
            listOf(buildJsonObject { put("thoughtSignature", "sig-2") }),
        )

        val texts = messages.last().parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("Hello", ""), texts.map { it.text })
        assertEquals(
            listOf("sig-1", "sig-2"),
            texts.map { it.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature },
        )
    }

    @Test
    fun `stream unknown part is skipped`() {
        val messages = decodeStream(
            listOf(buildJsonObject { put("text", "ok") }, buildJsonObject { put("somethingNew", true) }),
        )
        assertEquals("ok", messages.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `non-stream signature-only part attaches to the preceding text`() {
        val parts = invokeParseMessageParts(buildJsonArray {
            add(buildJsonObject { put("text", "Hello") })
            add(buildJsonObject { put("thoughtSignature", "sig-1") })
            add(buildJsonObject { put("somethingNew", true) })
        })

        val text = parts.single() as UIMessagePart.Text
        assertEquals("Hello", text.text)
        assertEquals("sig-1", text.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    private fun decodeStream(vararg events: List<JsonObject>): List<UIMessage> {
        val decoder = GoogleStreamDecoder("response-1", "gemini-test")
        val handler = StreamChunkHandler(Model(modelId = "gemini-test"))
        var messages = listOf(UIMessage.user("hi"))
        events.forEach { parts ->
            val event = SseEvent(data = json.encodeToString(buildJsonObject {
                putJsonArray("candidates") {
                    add(buildJsonObject {
                        put("content", buildJsonObject {
                            put("role", "model")
                            put("parts", JsonArray(parts))
                        })
                    })
                }
            }))
            decoder.accept(event).chunks.forEach { messages = handler.handle(messages, it) }
        }
        decoder.onClosed().forEach { messages = handler.handle(messages, it) }
        return messages
    }

    private fun replayModelParts(messages: List<UIMessage>): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
        method.isAccessible = true
        val contents = method.invoke(provider, messages) as JsonArray
        return contents[1].jsonObject["parts"]!!.jsonArray
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeParseMessageParts(parts: JsonArray): List<UIMessagePart> {
        val method = GoogleProvider::class.java.getDeclaredMethod("parseMessageParts", JsonArray::class.java)
        method.isAccessible = true
        return method.invoke(provider, parts) as List<UIMessagePart>
    }
}
