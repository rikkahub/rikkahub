package me.rerere.ai.provider.stream

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTraceReplayTest {
    @Test
    fun `replay DeepSeek Claude mixed tool trace`() {
        assertTrace(
            "generated/claude/deepseek-anthropic-tool",
            ClaudeStreamDecoder(),
            ::assertClaudeMixedToolTraceSemantics,
        )
    }

    @Test
    fun `replay OpenRouter Claude protocol trace`() {
        assertTrace("generated/claude/openrouter-claude-tool", ClaudeStreamDecoder())
    }

    @Test
    fun `replay Gemini protocol trace`() {
        assertTrace(
            "generated/google/gemini-tool",
            GoogleStreamDecoder(responseId = "google-trace", model = "gemini-trace-model"),
        )
    }

    @Test
    fun `replay Gemini image generation trace`() {
        assertTrace(
            "generated/google/gemini-image",
            GoogleStreamDecoder(responseId = "google-image-trace", model = "gemini-3.1-flash-image"),
            ::assertImageTraceSemantics,
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
    fun `replay OpenRouter Chat Completions trace`() {
        assertTrace(
            "generated/openai-chat/openrouter-completions-tool",
            ChatCompletionsStreamDecoder(),
            ::assertOpenRouterCompletionsTraceSemantics,
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
    fun `replay DeepSeek Responses API server tool trace`() {
        assertTrace(
            "generated/openai-responses/deepseek-responses-server-tool",
            ResponseApiStreamDecoder(),
            ::assertServerToolTraceSemantics,
        )
    }

    @Test
    fun `replay OpenAI Responses API trace`() {
        assertTrace(
            "generated/openai-responses/openai-responses-tool",
            ResponseApiStreamDecoder(),
        )
    }

    @Test
    fun `replay OpenRouter Responses API trace`() {
        assertTrace(
            "generated/openai-responses/openrouter-gpt-responses-tool",
            ResponseApiStreamDecoder(),
        )
    }

    private fun assertTrace(
        path: String,
        decoder: StreamChunkDecoder,
        assertSemantics: (String, UIMessage, List<StreamChunk>) -> Unit = ::assertToolTraceSemantics,
    ) {
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
        assertSemantics(path, actualMessage, chunks)

        val actual = actualMessage.toTraceSnapshot()
        val expected = if (System.getenv(UPDATE_SNAPSHOTS_ENV) == "true") {
            updateSnapshot(path, actual)
            actual
        } else {
            resource("stream-traces/$path/expected.json").let(json::parseToJsonElement)
        }
        assertEquals(expected, actual)
    }

    private fun assertToolTraceSemantics(path: String, message: UIMessage, chunks: List<StreamChunk>) {
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

    private fun assertOpenRouterCompletionsTraceSemantics(
        path: String,
        message: UIMessage,
        chunks: List<StreamChunk>,
    ) {
        assertToolTraceSemantics(path, message, chunks)

        val reasoningDetails = message.parts
            .filterIsInstance<UIMessagePart.Reasoning>()
            .firstNotNullOfOrNull { it.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails }
        assertEquals("$path should preserve one structured reasoning block", 1, reasoningDetails?.size)

        val reasoningDetail = reasoningDetails?.single()?.jsonObject
        assertEquals(
            "$path should preserve the Anthropic reasoning format",
            "anthropic-claude-v1",
            reasoningDetail?.get("format")?.jsonPrimitive?.contentOrNull,
        )
        assertTrue(
            "$path should preserve the Anthropic reasoning signature",
            !reasoningDetail?.get("signature")?.jsonPrimitive?.contentOrNull.isNullOrBlank(),
        )
    }

    private fun assertClaudeMixedToolTraceSemantics(
        path: String,
        message: UIMessage,
        chunks: List<StreamChunk>,
    ) {
        val clientTool = message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        val serverTool = message.parts.filterIsInstance<UIMessagePart.ServerTool>().single()
        val clientToolStartIndex = chunks.indexOfFirst { it is StreamChunk.ToolCallStart }
        val serverToolStartIndex = chunks.indexOfFirst { it is StreamChunk.ServerToolStart }
        val serverToolEndIndex = chunks.indexOfFirst { it is StreamChunk.ServerToolEnd }
        val finishIndex = chunks.indexOfFirst { it is StreamChunk.Finish }

        assertEquals("$path should emit exactly one Finish", 1, chunks.count { it is StreamChunk.Finish })
        assertEquals("list_memories", clientTool.toolName)
        assertEquals(
            "Kotlin",
            json.parseToJsonElement(clientTool.input).jsonObject["topic"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("web_search", serverTool.toolName)
        assertEquals(ServerToolStatus.COMPLETED, serverTool.status)
        assertTrue("$path should preserve the server tool input", serverTool.input != null)
        assertTrue("$path should preserve server search results", (serverTool.output as? JsonArray)?.isNotEmpty() == true)
        assertEquals(
            "server_tool_use",
            serverTool.metadataAs<ServerToolMetadata>()?.call
                ?.get("type")?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "web_search_tool_result",
            serverTool.metadataAs<ServerToolMetadata>()?.result
                ?.get("type")?.jsonPrimitive?.contentOrNull,
        )
        assertTrue("$path should emit the client tool before the server tool", clientToolStartIndex in 0..<serverToolStartIndex)
        assertTrue("$path should finish the server tool before the response", serverToolEndIndex in 0..<finishIndex)
    }

    private fun assertServerToolTraceSemantics(
        path: String,
        message: UIMessage,
        chunks: List<StreamChunk>,
    ) {
        val tools = message.parts.filterIsInstance<UIMessagePart.ServerTool>()

        assertEquals("$path should emit exactly one Finish", 1, chunks.count { it is StreamChunk.Finish })
        assertTrue("$path should emit ServerToolStart", chunks.any { it is StreamChunk.ServerToolStart })
        assertTrue("$path should emit ServerToolEnd", chunks.any { it is StreamChunk.ServerToolEnd })
        assertTrue("$path should contain server tools", tools.isNotEmpty())
        assertEquals("$path should only use web_search", setOf("web_search"), tools.map { it.toolName }.toSet())
        assertEquals("$path should preserve distinct IDs", tools.size, tools.map { it.toolCallId }.toSet().size)
        assertTrue("$path should preserve every tool input", tools.all { it.input != null })
        assertTrue("$path should finish every server tool", tools.all(UIMessagePart.ServerTool::isFinished))
        assertTrue(
            "$path should contain a completed server tool",
            tools.any { it.status == ServerToolStatus.COMPLETED },
        )

        if (path.contains("openai-responses")) {
            assertTrue("$path should preserve OpenAI call items", tools.all {
                it.metadataAs<ServerToolMetadata>()?.call
                    ?.get("type")?.jsonPrimitive?.contentOrNull == "web_search_call"
            })
            assertTrue("$path should not synthesize OpenAI search output", tools.all { it.output == null })
            assertTrue(
                "$path should preserve failed OpenAI server tool status",
                tools.any { it.status == ServerToolStatus.FAILED },
            )
        } else {
            assertTrue("$path should preserve Claude call blocks", tools.all {
                it.metadataAs<ServerToolMetadata>()?.call
                    ?.get("type")?.jsonPrimitive?.contentOrNull == "server_tool_use"
            })
            assertTrue("$path should preserve Claude result blocks", tools.all {
                it.metadataAs<ServerToolMetadata>()?.result
                    ?.get("type")?.jsonPrimitive?.contentOrNull == "web_search_tool_result"
            })
            assertTrue("$path should preserve Claude search results", tools.all {
                (it.output as? JsonArray)?.isNotEmpty() == true
            })
        }
    }

    private fun assertImageTraceSemantics(path: String, message: UIMessage, chunks: List<StreamChunk>) {
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        val images = message.parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals("$path should emit exactly one Finish", 1, chunks.count { it is StreamChunk.Finish })
        assertTrue("$path should contain non-empty reasoning", reasoning.any { it.reasoning.isNotBlank() })
        assertEquals("$path should contain exactly one generated image", 1, images.size)
        assertTrue("$path should emit ImageStart", chunks.any { it is StreamChunk.ImageStart })
        assertTrue("$path should emit ImageDelta", chunks.any { it is StreamChunk.ImageDelta })
        assertTrue("$path should emit ImageEnd", chunks.any { it is StreamChunk.ImageEnd })

        val imageUrl = images.single().url
        assertTrue("$path should produce an image data URL", imageUrl.startsWith("data:image/"))
        assertTrue("$path should produce a base64 image", imageUrl.contains(";base64,"))
        val imageBytes = Base64.getDecoder().decode(imageUrl.substringAfter(","))
        assertTrue(
            "$path should produce non-empty image bytes",
            imageBytes.isNotEmpty(),
        )
        assertTrue("$path should preserve the JPEG mime type", imageUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue(
            "$path should contain JPEG bytes",
            imageBytes.size >= 3 &&
                imageBytes[0] == 0xff.toByte() &&
                imageBytes[1] == 0xd8.toByte() &&
                imageBytes[2] == 0xff.toByte(),
        )

        val providerSignatures = loadEvents(path).flatMap { event ->
            val response = json.parseToJsonElement(event.data).jsonObject
            response["candidates"]?.jsonArray.orEmpty().flatMap { candidate ->
                candidate.jsonObject["content"]?.jsonObject
                    ?.get("parts")?.jsonArray.orEmpty()
                    .mapNotNull { part ->
                        part.jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    }
            }
        }
        assertEquals("$path should contain exactly one provider thought signature", 1, providerSignatures.size)

        val decodedSignature = requireNotNull(
            images.single().metadataAs<GoogleThoughtMetadata>()?.thoughtSignature,
        ) { "$path should preserve a decoded thought signature" }
        assertEquals(
            "$path should preserve the provider thought signature exactly",
            providerSignatures.single(),
            decodedSignature,
        )
        assertTrue(
            "$path should contain a valid non-empty base64 thought signature",
            Base64.getDecoder().decode(decodedSignature).isNotEmpty(),
        )
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

    private fun UIMessage.toTraceSnapshot(): JsonObject = buildJsonObject {
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
                            put("id", part.toolCallId)
                            put("name", part.toolName)
                            put("input", json.parseToJsonElement(part.input))
                        }
                        is UIMessagePart.ServerTool -> {
                            put("type", "server_tool")
                            put("id", part.toolCallId)
                            put("name", part.toolName)
                            put("status", part.status.name)
                            part.input?.let { put("input", it) }
                            (part.output as? JsonArray)?.let { output ->
                                put("resultCount", output.size)
                                putJsonArray("resultTypes") {
                                    output.mapNotNull { result ->
                                        (result as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
                                    }.distinct().forEach { add(it) }
                                }
                            }
                            part.metadataAs<ServerToolMetadata>()?.let { metadata ->
                                metadata.call?.get("type")?.jsonPrimitive?.contentOrNull?.let {
                                    put("callType", it)
                                }
                                metadata.result?.get("type")?.jsonPrimitive?.contentOrNull?.let {
                                    put("resultType", it)
                                }
                            }
                        }
                        is UIMessagePart.Image -> {
                            put("type", "image")
                            val header = part.url.substringBefore(",")
                            val bytes = Base64.getDecoder().decode(part.url.substringAfter(","))
                            put("mimeType", header.removePrefix("data:").substringBefore(";"))
                            put("byteCount", bytes.size)
                            put("sha256", bytes.sha256())
                        }
                        else -> error("Unsupported trace part: ${part::class.simpleName}")
                    }
                    if (part !is UIMessagePart.ServerTool) {
                        part.metadata?.let { put("metadata", it) }
                    }
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

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val UPDATE_SNAPSHOTS_ENV = "UPDATE_STREAM_TRACE_SNAPSHOTS"
        val prettyJson = Json { prettyPrint = true }
    }
}
