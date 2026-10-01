package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.GoogleInteractionsMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class InteractionsApiTest {
    private val api = InteractionsAPI(OkHttpClient())

    @Test
    fun `request body uses stateless interactions schema`() {
        val body = api.buildRequestBody(
            messages = listOf(
                UIMessage.system("You are a cat."),
                UIMessage.user("Hello there"),
            ),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-3.8-flash",
                    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                    tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext),
                ),
                temperature = 0.5f,
                maxTokens = 1024,
                tools = listOf(testTool()),
                reasoningLevel = ReasoningLevel.MEDIUM,
            ),
            stream = true,
        )

        assertEquals("gemini-3.8-flash", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertEquals("You are a cat.", body["system_instruction"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("previous_interaction_id"))

        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user_input", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Hello there",
            input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        val generationConfig = body["generation_config"]!!.jsonObject
        assertEquals("medium", generationConfig["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("auto", generationConfig["thinking_summaries"]!!.jsonPrimitive.content)
        assertEquals(1024, generationConfig["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse(generationConfig.containsKey("thinkingConfig"))

        // 函数工具与内置工具是同一个扁平数组，不再嵌套 functionDeclarations
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("function", "google_search", "url_context"), tools.map {
            it["type"]!!.jsonPrimitive.content
        })
        assertEquals("save_weather", tools[0]["name"]!!.jsonPrimitive.content)

        // Gemini API 的 Interactions 不接受 safety_settings
        assertFalse(body.containsKey("safety_settings"))
    }

    @Test
    fun `reasoning level maps to thinking level only`() {
        fun thinkingLevel(modelId: String, level: ReasoningLevel) = api.buildRequestBody(
            messages = listOf(UIMessage.user("hi")),
            params = TextGenerationParams(
                model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
                reasoningLevel = level,
            ),
            stream = false,
        )["generation_config"]!!.jsonObject["thinking_level"]?.jsonPrimitive?.content

        assertNull(thinkingLevel("gemini-3.8-flash", ReasoningLevel.AUTO))
        assertEquals("minimal", thinkingLevel("gemini-3-flash-preview", ReasoningLevel.OFF))
        assertEquals("low", thinkingLevel("gemini-2.5-flash", ReasoningLevel.OFF))
        assertEquals("high", thinkingLevel("gemini-2.5-pro", ReasoningLevel.XHIGH))
    }

    @Test
    fun `image output model requests text and image response formats`() {
        val body = api.buildRequestBody(
            messages = listOf(UIMessage.system("ignored"), UIMessage.user("draw a cat")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-3.1-flash-image",
                    outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                ),
            ),
            stream = true,
        )

        assertEquals(
            listOf("text", "image"),
            body["response_format"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
        assertFalse(body.containsKey("system_instruction"))
        assertFalse(body.containsKey("generation_config"))
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun `history is replayed as steps with signatures`() {
        val searchCall = buildJsonObject {
            put("type", "google_search_call")
            put("id", "gs_1")
            put("signature", "search-call-signature")
        }
        val searchResult = buildJsonObject {
            put("type", "google_search_result")
            put("call_id", "gs_1")
            put("signature", "search-result-signature")
        }
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ServerTool(
                    toolCallId = "gs_1",
                    toolName = "google_search",
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                        call = searchCall,
                        result = searchResult,
                    ).toMetadata(),
                ),
                reasoning("I should check the weather.", GoogleInteractionsMetadata("thought-signature").toMetadata()),
                // generateContent 协议的签名不能当作 Interactions 的 thought step 回传
                reasoning("foreign thought", GoogleThoughtMetadata("generate-content-signature").toMetadata()),
                UIMessagePart.Tool(
                    toolCallId = "fc_1",
                    toolName = "get_weather",
                    input = """{"location":"Mount Elbrus"}""",
                    output = listOf(UIMessagePart.Text("""{"weather":"snow"}""")),
                    metadata = GoogleInteractionsMetadata("call-signature").toMetadata(),
                ),
                UIMessagePart.Text("It is snowing."),
            ),
        )

        val input = api.buildInput(
            listOf(UIMessage.user("How is the weather?"), assistant, UIMessage.user("Thanks"))
        ).map { it.jsonObject }

        assertEquals(
            listOf(
                "user_input",
                "google_search_call",
                "google_search_result",
                "thought",
                "function_call",
                "function_result",
                "model_output",
                "user_input",
            ),
            input.map { it["type"]!!.jsonPrimitive.content },
        )
        assertEquals(searchCall, input[1])
        assertEquals(searchResult, input[2])

        val thought = input[3]
        assertEquals("thought-signature", thought["signature"]!!.jsonPrimitive.content)
        assertEquals(
            "I should check the weather.",
            thought["summary"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        val call = input[4]
        assertEquals("fc_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("Mount Elbrus", call["arguments"]!!.jsonObject["location"]!!.jsonPrimitive.content)
        assertEquals("call-signature", call["signature"]!!.jsonPrimitive.content)

        val result = input[5]
        assertEquals("fc_1", result["call_id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", result["name"]!!.jsonPrimitive.content)
        assertEquals(
            """{"weather":"snow"}""",
            result["result"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        assertEquals(
            "It is snowing.",
            input[6]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `server tools from other protocols and unpaired calls are not replayed`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ServerTool(
                    toolCallId = "other",
                    toolName = "google_search",
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        call = buildJsonObject { put("toolCall", buildJsonObject {}) },
                        result = buildJsonObject { put("toolResponse", buildJsonObject {}) },
                    ).toMetadata(),
                ),
                UIMessagePart.ServerTool(
                    toolCallId = "interrupted",
                    toolName = "google_search",
                    status = ServerToolStatus.IN_PROGRESS,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                        call = buildJsonObject { put("type", "google_search_call") },
                    ).toMetadata(),
                ),
                UIMessagePart.Text("answer"),
            ),
        )

        val input = api.buildInput(listOf(assistant)).map { it.jsonObject }

        assertEquals(listOf("model_output"), input.map { it["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `stream decodes thought and text steps`() {
        val (chunks, message) = decode(
            """{"interaction":{"id":"v1_abc","status":"in_progress","object":"interaction","model":"gemini-3.8-flash"},"event_type":"interaction.created"}""",
            """{"interaction_id":"v1_abc","status":"in_progress","event_type":"interaction.status_update"}""",
            """{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""",
            """{"index":0,"delta":{"content":{"text":"**Euclid**\n","type":"text"},"type":"thought_summary"},"event_type":"step.delta"}""",
            """{"index":0,"delta":{"signature":"sig-0","type":"thought_signature"},"event_type":"step.delta"}""",
            """{"index":0,"event_type":"step.stop"}""",
            """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""",
            """{"index":1,"delta":{"text":"The GCD ","type":"text"},"event_type":"step.delta"}""",
            """{"index":1,"delta":{"text":"is 21.","type":"text"},"event_type":"step.delta"}""",
            """{"index":1,"delta":{"type":"text_annotation_delta","annotations":[{"type":"url_citation","url":"https://example.com","title":"Example","start_index":0,"end_index":3}]},"event_type":"step.delta"}""",
            """{"index":1,"event_type":"step.stop"}""",
            """{"interaction":{"id":"v1_abc","status":"completed","usage":{"total_tokens":346,"total_input_tokens":11,"total_cached_tokens":2,"total_output_tokens":90,"total_thought_tokens":245},"model":"gemini-3.8-flash"},"event_type":"interaction.completed"}""",
        )

        val reasoning = message.parts[0] as UIMessagePart.Reasoning
        assertEquals("**Euclid**\n", reasoning.reasoning)
        assertEquals("sig-0", reasoning.metadataAs<GoogleInteractionsMetadata>()?.signature)
        assertEquals("The GCD is 21.", (message.parts[1] as UIMessagePart.Text).text)
        assertEquals(2, message.parts.size)
        assertEquals(listOf(UIMessageAnnotation.UrlCitation("Example", "https://example.com")), message.annotations)
        assertEquals(TokenUsage(promptTokens = 11, completionTokens = 335, totalTokens = 346, cachedTokens = 2), message.usage)
        assertEquals(StreamChunk.Finish("completed", "v1_abc", "gemini-3.8-flash"), chunks.last())
    }

    @Test
    fun `stream decodes server tool and function call steps and replays them`() {
        val (chunks, message) = decode(
            """{"interaction":{"id":"v1_tools","status":"in_progress","model":"gemini-3.8-flash"},"event_type":"interaction.created"}""",
            """{"index":0,"step":{"id":"mkutnkgn","signature":"","type":"google_search_call"},"event_type":"step.start"}""",
            """{"index":0,"delta":{"signature":"call-sig","type":"google_search_call","arguments":{"queries":["largest mountain in Europe"]}},"event_type":"step.delta"}""",
            """{"index":0,"event_type":"step.stop"}""",
            """{"index":1,"step":{"call_id":"mkutnkgn","signature":"","type":"google_search_result"},"event_type":"step.start"}""",
            """{"index":1,"delta":{"signature":"result-sig","type":"google_search_result","is_error":false},"event_type":"step.delta"}""",
            """{"index":1,"event_type":"step.stop"}""",
            """{"index":2,"step":{"type":"thought"},"event_type":"step.start"}""",
            """{"index":2,"delta":{"signature":"thought-sig","type":"thought_signature"},"event_type":"step.delta"}""",
            """{"index":2,"event_type":"step.stop"}""",
            """{"index":3,"step":{"id":"ktr5aysg","type":"function_call","name":"get_weather","arguments":{}},"event_type":"step.start"}""",
            """{"index":3,"delta":{"arguments":"{\"location\":","type":"arguments_delta"},"event_type":"step.delta"}""",
            """{"index":3,"delta":{"arguments":"\"Mount Elbrus, Russia\"}","type":"arguments_delta"},"event_type":"step.delta"}""",
            """{"index":3,"event_type":"step.stop"}""",
            """{"interaction":{"id":"v1_tools","status":"requires_action","usage":{"total_tokens":299,"total_input_tokens":138,"total_output_tokens":20,"total_thought_tokens":141}},"event_type":"interaction.completed"}""",
        )

        assertEquals(StreamChunk.Finish("requires_action", "v1_tools", "gemini-3.8-flash"), chunks.last())

        val serverTool = message.parts[0] as UIMessagePart.ServerTool
        assertEquals("mkutnkgn", serverTool.toolCallId)
        assertEquals("google_search", serverTool.toolName)
        assertEquals(ServerToolStatus.COMPLETED, serverTool.status)
        assertEquals(
            "largest mountain in Europe",
            serverTool.input!!.jsonObject["queries"]!!.jsonArray[0].jsonPrimitive.content,
        )
        val serverToolMetadata = serverTool.metadataAs<ServerToolMetadata>()!!
        assertEquals(ServerToolProtocol.GOOGLE_INTERACTIONS, serverToolMetadata.protocol)
        assertEquals("call-sig", serverToolMetadata.call!!["signature"]!!.jsonPrimitive.content)
        assertEquals("result-sig", serverToolMetadata.result!!["signature"]!!.jsonPrimitive.content)

        val reasoning = message.parts[1] as UIMessagePart.Reasoning
        assertEquals("", reasoning.reasoning)
        assertEquals("thought-sig", reasoning.metadataAs<GoogleInteractionsMetadata>()?.signature)

        val tool = message.parts[2] as UIMessagePart.Tool
        assertEquals("ktr5aysg", tool.toolCallId)
        assertEquals("get_weather", tool.toolName)
        assertEquals("""{"location":"Mount Elbrus, Russia"}""", tool.input)

        // 工具执行完成后，下一轮请求需要把上面的 step 原样带回去
        val executed = message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(output = listOf(UIMessagePart.Text("-12C"))) else part
        })
        val input = api.buildInput(listOf(UIMessage.user("search and report weather"), executed))
            .map { it.jsonObject }

        assertEquals(
            listOf(
                "user_input",
                "google_search_call",
                "google_search_result",
                "thought",
                "function_call",
                "function_result",
            ),
            input.map { it["type"]!!.jsonPrimitive.content },
        )
        assertEquals(
            json.parseToJsonElement(
                """{"id":"mkutnkgn","signature":"call-sig","type":"google_search_call","arguments":{"queries":["largest mountain in Europe"]}}"""
            ),
            input[1],
        )
        assertEquals(
            json.parseToJsonElement(
                """{"call_id":"mkutnkgn","signature":"result-sig","type":"google_search_result","is_error":false}"""
            ),
            input[2],
        )
        assertEquals("thought-sig", input[3]["signature"]!!.jsonPrimitive.content)
        assertFalse(input[3].containsKey("summary"))
        assertEquals("ktr5aysg", input[4]["id"]!!.jsonPrimitive.content)
        assertEquals("ktr5aysg", input[5]["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stream decodes interleaved text and images`() {
        val (_, message) = decode(
            """{"index":0,"step":{"type":"model_output","content":[{"type":"text","text":"Part 1"}]},"event_type":"step.start"}""",
            """{"index":0,"delta":{"mime_type":"image/jpeg","data":"AAAA","type":"image"},"event_type":"step.delta"}""",
            """{"index":0,"delta":{"text":"Part 2","type":"text"},"event_type":"step.delta"}""",
            """{"index":0,"delta":{"mime_type":"image/jpeg","data":"BBBB","type":"image"},"event_type":"step.delta"}""",
            """{"index":0,"event_type":"step.stop"}""",
            """{"interaction":{"id":"v1_image","status":"completed"},"event_type":"interaction.completed"}""",
        )

        assertEquals(
            listOf(
                UIMessagePart.Text("Part 1"),
                UIMessagePart.Image("data:image/jpeg;base64,AAAA"),
                UIMessagePart.Text("Part 2"),
                UIMessagePart.Image("data:image/jpeg;base64,BBBB"),
            ),
            message.parts,
        )
    }

    @Test
    fun `stream finishes once and tolerates unknown events`() {
        val decoder = InteractionsStreamDecoder(fallbackModel = "gemini-3.8-flash")

        assertTrue(decoder.accept(event("""{"event_type":"something.new","foo":1}""")).chunks.isEmpty())
        assertTrue(
            decoder.accept(event("""{"index":0,"delta":{"type":"future_delta"},"event_type":"step.delta"}"""))
                .chunks.isEmpty()
        )
        decoder.accept(event("""{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}"""))
        decoder.accept(event("""{"index":1,"delta":{"text":"partial","type":"text"},"event_type":"step.delta"}"""))

        val done = decoder.accept(SseEvent(event = "done", data = "[DONE]"))
        assertTrue(done.completed)
        assertTrue(done.chunks.any { it is StreamChunk.TextEnd })
        assertEquals(StreamChunk.Finish(null, null, "gemini-3.8-flash"), done.chunks.last())
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `stream error event is surfaced`() {
        val decoder = InteractionsStreamDecoder()

        val error = assertThrows(HttpException::class.java) {
            decoder.accept(
                event(
                    """{"error":{"message":"Deadline expired before operation could complete.","code":"gateway_timeout"},"event_type":"error"}"""
                )
            )
        }

        assertEquals("Deadline expired before operation could complete.", error.message)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `non-streaming interaction is parsed from steps`() {
        val result = api.parseInteraction(
            json.parseToJsonElement(
                """
                {
                  "id": "int_456",
                  "status": "requires_action",
                  "model": "gemini-3.8-flash",
                  "steps": [
                    {"type": "google_search_call", "id": "gs_1", "arguments": {"queries": ["last Super Bowl winner"]}, "signature": "call-sig"},
                    {"type": "google_search_result", "call_id": "gs_1", "result": [{"search_suggestions": "<div></div>"}], "signature": "result-sig"},
                    {"type": "thought", "summary": [{"type": "text", "text": "Need the weather."}], "signature": "thought-sig"},
                    {"type": "model_output", "content": [
                      {"type": "text", "text": "The Chiefs won.", "annotations": [{"type": "url_citation", "url": "https://www.nfl.com", "title": "NFL.com"}]},
                      {"type": "image", "mime_type": "image/png", "data": "AAAA"}
                    ]},
                    {"type": "function_call", "id": "fc_1", "name": "get_weather", "arguments": {"location": "Boston, MA"}, "signature": "fc-sig"}
                  ],
                  "usage": {"total_input_tokens": 10, "total_output_tokens": 5, "total_thought_tokens": 3, "total_tokens": 18}
                }
                """.trimIndent()
            ).jsonObject
        )

        assertEquals("int_456", result.id)
        assertEquals("requires_action", result.finishReason)
        assertEquals(TokenUsage(promptTokens = 10, completionTokens = 8, totalTokens = 18), result.usage)
        assertEquals(listOf(UIMessageAnnotation.UrlCitation("NFL.com", "https://www.nfl.com")), result.message.annotations)

        val parts = result.message.parts
        assertEquals(5, parts.size)

        val serverTool = parts[0] as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.COMPLETED, serverTool.status)
        assertEquals("google_search", serverTool.toolName)
        val metadata = serverTool.metadataAs<ServerToolMetadata>()!!
        assertEquals("call-sig", metadata.call!!["signature"]!!.jsonPrimitive.content)
        assertEquals("result-sig", metadata.result!!["signature"]!!.jsonPrimitive.content)

        val reasoning = parts[1] as UIMessagePart.Reasoning
        assertEquals("Need the weather.", reasoning.reasoning)
        assertEquals("thought-sig", reasoning.metadataAs<GoogleInteractionsMetadata>()?.signature)

        assertEquals("The Chiefs won.", (parts[2] as UIMessagePart.Text).text)
        assertEquals("data:image/png;base64,AAAA", (parts[3] as UIMessagePart.Image).url)

        val tool = parts[4] as UIMessagePart.Tool
        assertEquals("fc_1", tool.toolCallId)
        assertEquals("Boston, MA", tool.inputAsJson().jsonObject["location"]!!.jsonPrimitive.content)
        assertEquals("fc-sig", tool.metadataAs<GoogleInteractionsMetadata>()?.signature)
    }

    private fun decode(vararg events: String): Pair<List<StreamChunk>, UIMessage> {
        val decoder = InteractionsStreamDecoder()
        val handler = StreamChunkHandler()
        val chunks = events.flatMap { decoder.accept(event(it)).chunks } + decoder.onClosed()
        val messages = chunks.fold(listOf(UIMessage.user("prompt"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        return chunks to messages.last()
    }

    private fun event(data: String): SseEvent {
        val type = (json.parseToJsonElement(data) as JsonObject)["event_type"]?.jsonPrimitive?.content
        return SseEvent(event = type, data = data)
    }

    private fun reasoning(text: String, metadata: JsonObject) = UIMessagePart.Reasoning(
        reasoning = text,
        createdAt = Clock.System.now(),
        finishedAt = Clock.System.now(),
        metadata = metadata,
    )

    private fun testTool() = Tool(
        name = "save_weather",
        description = "Save weather information",
        execute = { emptyList() },
    )
}
