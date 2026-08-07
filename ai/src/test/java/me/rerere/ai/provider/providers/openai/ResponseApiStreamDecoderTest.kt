package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolStatus
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

class ResponseApiStreamDecoderTest {
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
        assertEquals(ReasoningType.SUMMARY_TEXT, reasoningParts.single().reasoningType)
    }

    @Test
    fun `raw reasoning and summary with the same index should remain distinct`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_text.delta")
                put("item_id", "rs_test")
                put("content_index", 0)
                put("delta", "raw")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)

        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals(
            "summary",
            reasoningItem["summary"]?.jsonArray?.single()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content,
        )
        assertEquals(
            "raw",
            reasoningItem["content"]?.jsonArray?.single()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `web search events should produce one completed server tool`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.added")
                put("item", webSearchItem("in_progress"))
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.searching")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.completed")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("item", webSearchItem("completed"))
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("search"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val tool = messages.last().parts.single() as UIMessagePart.ServerTool

        assertEquals("ws_1", tool.toolCallId)
        assertEquals("web_search", tool.toolName)
        assertEquals("search", tool.input?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(
            "web_search_call",
            tool.metadataAs<ServerToolMetadata>()?.call?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `non streaming response should parse web search without synthetic output`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray { add(webSearchItem("completed")) })
        })

        val tool = result.message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(null, tool.output)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should distinguish raw reasoning and summary`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_test")
                    put("encrypted_content", "encrypted")
                    put("summary", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "summary_text")
                            put("text", "summary")
                        })
                    })
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "reasoning_text")
                            put("text", "raw")
                        })
                    })
                })
            })
        })

        val reasoningParts = result.message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)
        reasoningParts.forEach {
            assertEquals("rs_test", it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
            assertEquals("encrypted", it.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        }
    }

    private fun webSearchItem(status: String) = buildJsonObject {
        put("type", "web_search_call")
        put("id", "ws_1")
        put("status", status)
        put("action", buildJsonObject {
            put("type", "search")
            put("query", "Kotlin")
        })
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
