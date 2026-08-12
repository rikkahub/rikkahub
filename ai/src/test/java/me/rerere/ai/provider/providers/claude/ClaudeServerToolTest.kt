package me.rerere.ai.provider.providers.claude

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeServerToolTest {
    private val provider = ClaudeProvider(OkHttpClient())

    @Test
    fun `non streaming response should pair server tool use and result`() {
        val message = provider.parseMessage(buildJsonArray {
            add(serverToolUse())
            add(serverToolResult())
        })

        val tool = message.parts.single() as UIMessagePart.ServerTool
        assertEquals("srvtoolu_1", tool.toolCallId)
        assertEquals("web_search", tool.toolName)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertTrue(tool.output is JsonArray)
        assertEquals(
            "web_search_tool_result",
            tool.metadataAs<ServerToolMetadata>()?.result?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `Claude server tool error result should be failed`() {
        val errorResult = buildJsonObject {
            put("type", "web_search_tool_result")
            put("tool_use_id", "srvtoolu_1")
            put("content", buildJsonObject {
                put("type", "web_search_tool_result_error")
                put("error_code", "max_uses_exceeded")
            })
        }
        val message = provider.parseMessage(buildJsonArray {
            add(serverToolUse())
            add(errorResult)
        })

        val tool = message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.FAILED, tool.status)
    }

    @Test
    fun `streaming response should merge input and result into server tool`() {
        val decoder = ClaudeStreamDecoder()
        val events = listOf(
            sse("message_start", buildJsonObject {
                put("message", buildJsonObject {
                    put("id", "msg_1")
                    put("model", "claude-test")
                })
            }),
            sse("content_block_start", buildJsonObject {
                put("index", 0)
                put("content_block", serverToolUse(input = buildJsonObject {}))
            }),
            sse("content_block_delta", buildJsonObject {
                put("index", 0)
                put("delta", buildJsonObject {
                    put("type", "input_json_delta")
                    put("partial_json", "{\"query\":\"Kotlin\"}")
                })
            }),
            sse("content_block_stop", buildJsonObject { put("index", 0) }),
            sse("content_block_start", buildJsonObject {
                put("index", 1)
                put("content_block", serverToolResult())
            }),
            sse("content_block_stop", buildJsonObject { put("index", 1) }),
            sse("message_stop", buildJsonObject {}),
        )

        val chunks = events.flatMap { decoder.accept(it).chunks }
        val handler = StreamChunkHandler(Model(modelId = "claude-test"))
        val messages = chunks.fold(listOf(UIMessage.user("search"))) { acc, chunk ->
            handler.handle(acc, chunk)
        }
        val tool = messages.last().parts.single() as UIMessagePart.ServerTool

        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertTrue(tool.output is JsonArray)
    }

    @Test
    fun `server tool history should replay original Claude blocks with final input`() {
        val call = serverToolUse(input = buildJsonObject {})
        val result = serverToolResult()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ServerTool(
                toolCallId = "srvtoolu_1",
                toolName = "web_search",
                input = buildJsonObject { put("query", "Kotlin") },
                output = result["content"],
                status = ServerToolStatus.COMPLETED,
                metadata = ServerToolMetadata(
                    protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                    call = call,
                    result = result,
                ).let {
                    json.encodeToJsonElement(ServerToolMetadata.serializer(), it).jsonObject
                },
            )),
        )

        val messages = buildMessages(listOf(UIMessage.user("search"), message))
        val assistantContent = messages.last().jsonObject["content"]?.jsonArray

        assertEquals(2, assistantContent?.size)
        assertEquals(
            "Kotlin",
            assistantContent?.get(0)?.jsonObject?.get("input")?.jsonObject
                ?.get("query")?.jsonPrimitive?.content,
        )
        assertEquals("web_search_tool_result", assistantContent?.get(1)?.jsonObject
            ?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel server tool history should replay all calls before results`() {
        val content = buildJsonArray {
            add(serverToolUse(id = "srvtoolu_1"))
            add(serverToolUse(id = "srvtoolu_2"))
            add(serverToolResult(id = "srvtoolu_1"))
            add(serverToolResult(id = "srvtoolu_2"))
        }
        val message = provider.parseMessage(content)

        val messages = buildMessages(listOf(UIMessage.user("search"), message))
        val replayedContent = messages.last().jsonObject["content"]?.jsonArray

        assertEquals(
            listOf(
                "server_tool_use",
                "server_tool_use",
                "web_search_tool_result",
                "web_search_tool_result",
            ),
            replayedContent?.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("srvtoolu_1", "srvtoolu_2"),
            replayedContent?.take(2)?.map { it.jsonObject["id"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("srvtoolu_1", "srvtoolu_2"),
            replayedContent?.drop(2)?.map {
                it.jsonObject["tool_use_id"]?.jsonPrimitive?.content
            },
        )
    }

    @Test
    fun `sequential server tool history should preserve interleaved block order`() {
        val content = buildJsonArray {
            add(serverToolUse(id = "srvtoolu_1"))
            add(serverToolResult(id = "srvtoolu_1"))
            add(serverToolUse(id = "srvtoolu_2"))
            add(serverToolResult(id = "srvtoolu_2"))
        }
        val message = provider.parseMessage(content)

        val messages = buildMessages(listOf(UIMessage.user("search"), message))
        val replayedContent = messages.last().jsonObject["content"]?.jsonArray

        assertEquals(
            listOf(
                "server_tool_use",
                "web_search_tool_result",
                "server_tool_use",
                "web_search_tool_result",
            ),
            replayedContent?.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `Claude request should skip Responses server tool history`() {
        val responseCall = buildJsonObject {
            put("type", "web_search_call")
            put("id", "ws_1")
            put("status", "completed")
        }
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ServerTool(
                toolCallId = "ws_1",
                toolName = "web_search",
                status = ServerToolStatus.COMPLETED,
                metadata = ServerToolMetadata(
                    protocol = ServerToolProtocol.OPENAI_RESPONSES,
                    call = responseCall,
                ).toMetadata(),
            )),
        )

        val messages = buildMessages(listOf(UIMessage.user("search"), message))

        assertEquals(1, messages.size)
        assertEquals("user", messages.single().jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Claude request should enable hosted web search`() {
        val body = buildRequest(
            providerSetting = ProviderSetting.Claude(),
            messages = listOf(UIMessage.user("search")),
            params = TextGenerationParams(model = Model(
                modelId = "claude-test",
                tools = setOf(BuiltInTools.Search),
            )),
        )

        val tool = body["tools"]?.jsonArray?.single()?.jsonObject
        assertEquals("web_search_20250305", tool?.get("type")?.jsonPrimitive?.content)
        assertEquals("web_search", tool?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming pause turn should continue inside Claude provider`() = runBlocking {
        val model = Model(modelId = "claude-test")
        val requests = mutableListOf<List<UIMessage>>()
        val responses = listOf(
            TextGenerationResult(
                id = "msg_1",
                model = "claude-test",
                message = provider.parseMessage(buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "searching")
                    })
                    add(serverToolUse())
                }),
                finishReason = "pause_turn",
                usage = TokenUsage(promptTokens = 10, completionTokens = 2, totalTokens = 12),
            ),
            TextGenerationResult(
                id = "msg_2",
                model = "claude-test",
                message = provider.parseMessage(buildJsonArray {
                    add(serverToolResult())
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "done")
                    })
                }),
                finishReason = "end_turn",
                usage = TokenUsage(promptTokens = 5, completionTokens = 3, totalTokens = 8),
            ),
        )

        val result = generateClaudeWithPauseTurn(listOf(UIMessage.user("search")), model) { messages ->
            requests += messages
            responses[requests.lastIndex]
        }

        assertEquals(2, requests.size)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), requests[1].map { it.role })
        val replayedTool = requests[1].last().parts.filterIsInstance<UIMessagePart.ServerTool>().single()
        assertEquals(ServerToolStatus.IN_PROGRESS, replayedTool.status)
        assertEquals(
            "server_tool_use",
            replayedTool.metadataAs<ServerToolMetadata>()?.call?.get("type")?.jsonPrimitive?.content,
        )
        assertEquals("end_turn", result.finishReason)
        assertEquals("done", result.message.parts.filterIsInstance<UIMessagePart.Text>().last().text)
        assertEquals(ServerToolStatus.COMPLETED, result.message.parts
            .filterIsInstance<UIMessagePart.ServerTool>().single().status)
        assertEquals(TokenUsage(15, 5, 0, 20), result.usage)

        val finalToolMetadata = result.message.parts
            .filterIsInstance<UIMessagePart.ServerTool>()
            .single()
            .metadataAs<ServerToolMetadata>()
        assertEquals(1, finalToolMetadata?.callIndex)
        assertEquals(2, finalToolMetadata?.resultIndex)
        val replayedContent = buildMessages(listOf(UIMessage.user("search"), result.message))
            .last().jsonObject["content"]?.jsonArray
        assertEquals(
            listOf("text", "server_tool_use", "web_search_tool_result", "text"),
            replayedContent?.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `streaming pause turn should expose one logical stream`() = runBlocking {
        val model = Model(modelId = "claude-test")
        val call = serverToolUse()
        val result = serverToolResult()
        val requests = mutableListOf<List<UIMessage>>()
        val passes = listOf(
            flowOf(
                StreamChunk.TextStart("intro"),
                StreamChunk.TextDelta("intro", "searching"),
                StreamChunk.TextEnd("intro"),
                StreamChunk.ServerToolStart(
                    id = "srvtoolu_1",
                    toolName = "web_search",
                    input = call["input"],
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                        call = call,
                        callIndex = 1,
                    ).toMetadata(),
                ),
                StreamChunk.ServerToolInputEnd("srvtoolu_1"),
                StreamChunk.Usage(TokenUsage(10, 2, 0, 12)),
                StreamChunk.Finish("pause_turn", "msg_1", "claude-test"),
            ),
            flowOf(
                StreamChunk.ServerToolEnd(
                    id = "srvtoolu_1",
                    output = result["content"],
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                        result = result,
                        resultIndex = 0,
                    ).toMetadata(),
                ),
                StreamChunk.TextStart("text_1"),
                StreamChunk.TextDelta("text_1", "done"),
                StreamChunk.TextEnd("text_1"),
                StreamChunk.Usage(TokenUsage(5, 3, 0, 8)),
                StreamChunk.Finish("end_turn", "msg_2", "claude-test"),
            ),
        )

        val chunks = streamClaudeWithPauseTurn(listOf(UIMessage.user("search")), model) { messages ->
            requests += messages
            passes[requests.lastIndex]
        }.toList()

        assertEquals(2, requests.size)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), requests[1].map { it.role })
        assertEquals(
            listOf("end_turn"),
            chunks.filterIsInstance<StreamChunk.Finish>().map { it.finishReason },
        )
        assertEquals(
            TokenUsage(15, 5, 0, 20),
            chunks.filterIsInstance<StreamChunk.Usage>().last().usage,
        )

        val handler = StreamChunkHandler(model)
        val outputMessages = chunks.fold(listOf(UIMessage.user("search"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val assistant = outputMessages.last()
        assertEquals("done", assistant.parts.filterIsInstance<UIMessagePart.Text>().last().text)
        assertEquals(ServerToolStatus.COMPLETED, assistant.parts
            .filterIsInstance<UIMessagePart.ServerTool>().single().status)
        val finalToolMetadata = assistant.parts
            .filterIsInstance<UIMessagePart.ServerTool>()
            .single()
            .metadataAs<ServerToolMetadata>()
        assertEquals(1, finalToolMetadata?.callIndex)
        assertEquals(2, finalToolMetadata?.resultIndex)
        val replayedContent = buildMessages(listOf(UIMessage.user("search"), assistant))
            .last().jsonObject["content"]?.jsonArray
        assertEquals(
            listOf("text", "server_tool_use", "web_search_tool_result", "text"),
            replayedContent?.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
    }

    private fun buildRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): kotlinx.serialization.json.JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(provider, providerSetting, messages, params, false)
            as kotlinx.serialization.json.JsonObject
    }

    private fun buildMessages(messages: List<UIMessage>): JsonArray {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            me.rerere.ai.provider.ClaudePromptCacheTtl::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            messages,
            false,
            me.rerere.ai.provider.ClaudePromptCacheTtl.FIVE_MINUTES,
        ) as JsonArray
    }

    private fun serverToolUse(
        id: String = "srvtoolu_1",
        input: kotlinx.serialization.json.JsonObject = buildJsonObject { put("query", "Kotlin") },
    ) = buildJsonObject {
        put("type", "server_tool_use")
        put("id", id)
        put("name", "web_search")
        put("input", input)
    }

    private fun serverToolResult(id: String = "srvtoolu_1") = buildJsonObject {
        put("type", "web_search_tool_result")
        put("tool_use_id", id)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "web_search_result")
                put("title", "Kotlin")
                put("url", "https://kotlinlang.org")
            })
        })
    }

    private fun sse(event: String, payload: kotlinx.serialization.json.JsonObject): SseEvent =
        SseEvent(event = event, data = json.encodeToString(payload))
}
