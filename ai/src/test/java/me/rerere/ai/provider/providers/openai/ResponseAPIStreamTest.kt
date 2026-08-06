package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseAPIStreamTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `reasoning item without summary should preserve encrypted content`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        assertEquals(2, chunks.size)
        assertTrue(chunks[0] is StreamChunk.ReasoningStart)
        assertTrue(chunks[1] is StreamChunk.ReasoningEnd)

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoning = messages.last().parts.single() as UIMessagePart.Reasoning

        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        assertFalse(messages.last().isValidToUpload())

        // ResponseAPI must still replay metadata-only reasoning even though generic providers consider it empty.
        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_test", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning item should keep final metadata after summary done event`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.done")
                put("item_id", "rs_test")
                put("summary_index", 0)
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(1, reasoningParts.size)
        assertEquals("summary", reasoningParts.single().reasoning)
        assertEquals(
            "encrypted",
            reasoningParts.single().metadataAs<OpenAIReasoningMetadata>()?.encryptedContent,
        )
    }

    private fun reasoningItemEvent(type: String, encryptedContent: String? = null) = buildJsonObject {
        put("type", type)
        put("item", buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_test")
            encryptedContent?.let { put("encrypted_content", it) }
        })
    }

    private fun ResponseApiStreamDecoder.decode(payload: kotlinx.serialization.json.JsonObject): List<StreamChunk> =
        accept(SseEvent(
            event = payload["type"]?.jsonPrimitive?.content,
            data = json.encodeToString(payload),
        )).chunks
}
