package me.rerere.ai.provider.stream

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.providers.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.GoogleStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTraceReplayTest {
    @Test
    fun `replay DeepSeek Claude protocol trace`() {
        assertTrace("generated/claude/deepseek-anthropic-tool", ClaudeStreamDecoder())
    }

    @Test
    fun `replay Gemini protocol trace`() {
        assertTrace(
            "generated/google/gemini-tool",
            GoogleStreamDecoder(responseId = "google-trace", model = "gemini-trace-model"),
        )
    }

    @Test
    fun `replay DeepSeek Chat Completions trace`() {
        assertTrace(
            "generated/openai-chat/deepseek-chat-tool",
            ChatCompletionsStreamDecoder(),
        )
    }

    @Test
    fun `replay DeepSeek Responses API trace`() {
        assertTrace(
            "generated/openai-responses/deepseek-responses-tool",
            ResponseApiStreamDecoder(),
        )
    }

    @Test
    fun `replay OpenAI Responses API trace`() {
        assertTrace(
            "generated/openai-responses/openai-responses-tool",
            ResponseApiStreamDecoder(),
        )
    }

    private fun assertTrace(path: String, decoder: StreamChunkDecoder) {
        val handler = StreamChunkHandler(Model(modelId = "fixture-model"))
        var messages = listOf(UIMessage.user("fixture input"))
        val chunks = mutableListOf<StreamChunk>()

        loadEvents(path).forEach { event ->
            decoder.accept(event).chunks.forEach { chunk ->
                chunks += chunk
                messages = handler.handle(messages, chunk)
            }
        }
        decoder.onClosed().forEach { chunk ->
            chunks += chunk
            messages = handler.handle(messages, chunk)
        }

        val actualMessage = messages.last()
        assertTraceSemantics(path, actualMessage, chunks)

        val actual = actualMessage.toTraceSnapshot()
        val expected = if (System.getenv(UPDATE_SNAPSHOTS_ENV) == "true") {
            updateSnapshot(path, actual)
            actual
        } else {
            resource("stream-traces/$path/expected.json").let(json::parseToJsonElement)
        }
        assertEquals(expected, actual)
    }

    private fun assertTraceSemantics(path: String, message: UIMessage, chunks: List<StreamChunk>) {
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        val tools = message.parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals("$path should emit exactly one Finish", 1, chunks.count { it is StreamChunk.Finish })
        assertTrue("$path should contain non-empty reasoning", reasoning.any { it.reasoning.isNotBlank() })
        assertEquals("$path should contain three parallel tool calls", 3, tools.size)
        assertEquals("$path should use the search_web tool", setOf("search_web"), tools.map { it.toolName }.toSet())
        assertEquals("$path should emit distinct tool call IDs", tools.size, tools.map { it.toolCallId }.toSet().size)

        val queries = tools.map { tool ->
            json.parseToJsonElement(tool.input).jsonObject["query"]?.jsonPrimitive?.contentOrNull
        }
        assertTrue("$path should provide a query for every tool call", queries.all { !it.isNullOrBlank() })
        assertEquals("$path should contain three distinct searches", tools.size, queries.toSet().size)
    }

    private fun loadEvents(path: String): List<SseEvent> =
        resource("stream-traces/$path/events.jsonl")
            .lineSequence()
            .filter(String::isNotBlank)
            .map { json.decodeFromString<SseEvent>(it) }
            .toList()

    private fun resource(path: String): String = requireNotNull(
        javaClass.classLoader?.getResource(path),
    ) { "Missing test resource: $path" }.readText()

    private fun updateSnapshot(path: String, snapshot: JsonObject) {
        val target = File("src/test/resources/stream-traces/$path/expected.json")
        target.parentFile?.mkdirs()
        target.writeText(prettyJson.encodeToString(JsonObject.serializer(), snapshot) + "\n")
    }

    private fun UIMessage.toTraceSnapshot(): JsonObject {
        val stableToolIds = parts.filterIsInstance<UIMessagePart.Tool>()
            .mapIndexed { index, tool -> tool.toolCallId to "tool-${index + 1}" }
            .toMap()

        return buildJsonObject {
            put("role", role.name)
            put("parts", buildJsonArray {
                parts.forEach { part ->
                    add(buildJsonObject {
                        when (part) {
                            is UIMessagePart.Text -> {
                                put("type", "text")
                                put("text", part.text)
                            }
                            is UIMessagePart.Reasoning -> {
                                put("type", "reasoning")
                                put("text", part.reasoning)
                            }
                            is UIMessagePart.Tool -> {
                                put("type", "tool")
                                put("id", stableToolIds.getValue(part.toolCallId))
                                put("name", part.toolName)
                                put("input", json.parseToJsonElement(part.input))
                            }
                            is UIMessagePart.Image -> {
                                put("type", "image")
                                put("url", part.url)
                            }
                            else -> error("Unsupported trace part: ${part::class.simpleName}")
                        }
                        part.metadata?.toStableMetadata()?.let { put("metadata", it) }
                    })
                }
            })
            putJsonArray("annotations") {
                annotations.forEach { annotation ->
                    when (annotation) {
                        is UIMessageAnnotation.UrlCitation -> add(buildJsonObject {
                            put("type", "url_citation")
                            put("title", annotation.title)
                            put("url", annotation.url)
                        })
                    }
                }
            }
            usage?.let { usage ->
                putJsonObject("usage") {
                    put("promptTokens", usage.promptTokens)
                    put("completionTokens", usage.completionTokens)
                    put("cachedTokens", usage.cachedTokens)
                    put("totalTokens", usage.totalTokens)
                }
            }
            put("finished", finishedAt != null)
        }
    }

    private fun JsonObject.toStableMetadata(): JsonObject? = buildJsonObject {
        if (this@toStableMetadata["signature"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
            put("hasSignature", true)
        }
        if (this@toStableMetadata["reasoning_id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
            put("hasReasoningId", true)
        }
        if (this@toStableMetadata["encrypted_content"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
            put("hasEncryptedContent", true)
        }
        if (this@toStableMetadata["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) {
            put("hasThoughtSignature", true)
        }
    }.takeIf(JsonObject::isNotEmpty)

    private companion object {
        const val UPDATE_SNAPSHOTS_ENV = "UPDATE_STREAM_TRACE_SNAPSHOTS"
        val prettyJson = Json { prettyPrint = true }
    }
}
