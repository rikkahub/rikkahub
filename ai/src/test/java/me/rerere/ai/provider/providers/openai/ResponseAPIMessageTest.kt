package me.rerere.ai.provider.providers.openai

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ResponseAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI Response API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * ResponseAPI uses a different format than ChatCompletionsAPI:
 * - function_call items for tool invocations
 * - function_call_output items for tool results
 */
class ResponseAPIMessageTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    // Helper to invoke buildMessages method
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        return api.buildMessages(messages)
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, listOf(UIMessage.user("hello")), params, stream)
    }

    private fun createReasoningParams(reasoningLevel: ReasoningLevel = ReasoningLevel.OFF): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel
        )
    }

    @Test
    fun `multi-round tool calls should produce correct function_call and function_call_output pairs`() {
        // Scenario: Multiple tool calls in sequence
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure for ResponseAPI:
        // 1. user message
        // 2. assistant content (text)
        // 3. function_call (search)
        // 4. function_call_output (search result)
        // 5. assistant content (text)
        // 6. function_call (calculate)
        // 7. function_call_output (calculate result)
        // 8. assistant content (final text)

        // Collect function_call items
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertEquals("Should have 2 function_call items", 2, functionCalls.size)

        // Collect function_call_output items
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals("Should have 2 function_call_output items", 2, functionOutputs.size)

        // Verify first function_call
        val call1 = functionCalls[0].jsonObject
        assertEquals("call_1", call1["call_id"]?.jsonPrimitive?.content)
        assertEquals("search", call1["name"]?.jsonPrimitive?.content)

        // Verify first function_call_output
        val output1 = functionOutputs[0].jsonObject
        assertEquals("call_1", output1["call_id"]?.jsonPrimitive?.content)
        assertTrue(output1["output"]?.jsonPrimitive?.content?.contains("Search result") == true)

        // Verify second function_call
        val call2 = functionCalls[1].jsonObject
        assertEquals("call_2", call2["call_id"]?.jsonPrimitive?.content)
        assertEquals("calculate", call2["name"]?.jsonPrimitive?.content)

        // Verify second function_call_output
        val output2 = functionOutputs[1].jsonObject
        assertEquals("call_2", output2["call_id"]?.jsonPrimitive?.content)
        assertTrue(output2["output"]?.jsonPrimitive?.content?.contains("4") == true)
    }

    @Test
    fun `function_call should be immediately followed by function_call_output`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_abc", "my_tool", """{"x": 1}""", "result")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find function_call index
        var functionCallIndex = -1
        for (i in result.indices) {
            if (result[i].jsonObject["type"]?.jsonPrimitive?.content == "function_call") {
                functionCallIndex = i
                break
            }
        }

        assertTrue("Should find function_call", functionCallIndex >= 0)
        assertTrue("function_call_output should follow", functionCallIndex < result.size - 1)

        val nextItem = result[functionCallIndex + 1].jsonObject
        assertEquals("function_call_output", nextItem["type"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextItem["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool calls should produce sequential function_call and output pairs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Should have 3 function_calls and 3 function_call_outputs
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals(3, functionCalls.size)
        assertEquals(3, functionOutputs.size)

        // Verify each function_call is followed by its output (in pairs)
        val callIds = listOf("call_1", "call_2", "call_3")
        for (callId in callIds) {
            var callIndex = -1
            var outputIndex = -1
            for (i in result.indices) {
                val item = result[i].jsonObject
                if (item["type"]?.jsonPrimitive?.content == "function_call" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    callIndex = i
                }
                if (item["type"]?.jsonPrimitive?.content == "function_call_output" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    outputIndex = i
                }
            }
            assertTrue("Should find function_call for $callId", callIndex >= 0)
            assertTrue("Should find function_call_output for $callId", outputIndex >= 0)
            assertEquals("Output should immediately follow call for $callId",
                callIndex + 1, outputIndex)
        }
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                createExecutedTool("call_1", "test", "{}", "output"),
                UIMessagePart.Text("Goodbye")
            )
        )

        val messages = listOf(
            UIMessage.user("Hi"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant content messages
        val assistantContents = result.filter {
            val obj = it.jsonObject
            obj["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertTrue("Should have assistant content messages", assistantContents.isNotEmpty())

        // First assistant message should have "Hello world"
        val firstAssistant = assistantContents[0].jsonObject
        val content = firstAssistant["content"]
        val hasHello = when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.content.contains("Hello")
            content is JsonArray -> content.any {
                it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Hello") == true
            }
            else -> false
        }
        assertTrue("First assistant should contain 'Hello'", hasHello)
    }

    @Test
    fun `complex multi-round scenario with text and tools interleaved`() {
        val messages = listOf(
            UIMessage.user("Execute a complex task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting task"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    createExecutedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed successfully")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Count items
        val userMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }
        val assistantMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }
        val functionCalls = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals("Should have 1 user message", 1, userMessages)
        assertEquals("Should have 3 function_calls", 3, functionCalls)
        assertEquals("Should have 3 function_call_outputs", 3, functionOutputs)
        assertTrue("Should have multiple assistant messages", assistantMessages >= 1)

        // Verify the order: each function_call immediately followed by function_call_output
        var lastCallIndex = -1
        for (i in result.indices) {
            val item = result[i].jsonObject
            if (item["type"]?.jsonPrimitive?.content == "function_call") {
                assertTrue("function_call should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("function_call_output should follow",
                    "function_call_output", next["type"]?.jsonPrimitive?.content)
                assertTrue("call_id should match",
                    item["call_id"]?.jsonPrimitive?.content == next["call_id"]?.jsonPrimitive?.content)
                assertTrue("Order should be maintained", i > lastCallIndex)
                lastCallIndex = i
            }
        }
    }

    @Test
    fun `volc response api should not include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertFalse("volc should not include reasoning.summary", reasoning!!.containsKey("summary"))
    }

    @Test
    fun `openai response api should include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("auto", reasoning!!["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `volc response api should keep reasoning effort when non auto`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.LOW)
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("low", reasoning!!["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `response_created event yields in-progress Reasoning chunk`() {
        val event = parseToJsonObject("""{"type":"response.created","response":{"id":"resp_1"}}""")
        val chunk = api.parseResponseDelta(event)

        assertTrue("response.created must produce a chunk (live reasoning placeholder)", chunk != null)
        val parts = chunk!!.choices.first().delta!!.parts
        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals("delta must hold exactly one Reasoning part", 1, reasoning.size)
        assertTrue("placeholder reasoning must be in-progress (finishedAt == null)", reasoning.first().finishedAt == null)
        assertTrue(
            "metadata must be non-null or the merge reducer drops the empty placeholder",
            reasoning.first().metadata != null
        )
    }

    @Test
    fun `response_in_progress event yields in-progress Reasoning chunk`() {
        val event = parseToJsonObject("""{"type":"response.in_progress","response":{"id":"resp_1"}}""")
        val chunk = api.parseResponseDelta(event)

        assertTrue("response.in_progress must produce a chunk", chunk != null)
        val reasoning = chunk!!.choices.first().delta!!.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(1, reasoning.size)
        assertTrue(reasoning.first().finishedAt == null)
        assertTrue(reasoning.first().metadata != null)
    }

    @Test
    fun `summary delta merges into the same reasoning part as the placeholder`() {
        val createdChunk = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.created","response":{"id":"resp_1"}}""")
        )!!
        val summaryChunk = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.reasoning_summary_text.delta","item_id":"x","delta":"hello"}""")
        )!!

        val seed = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val afterCreated = seed.handleMessageChunk(createdChunk)
        val afterSummary = afterCreated.handleMessageChunk(summaryChunk)

        val reasoning = afterSummary.last().parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals("placeholder + summary delta must merge into ONE reasoning part", 1, reasoning.size)
        assertEquals("hello", reasoning.first().reasoning)
        assertTrue("still streaming, so finishedAt stays null", reasoning.first().finishedAt == null)
    }

    @Test
    fun `reasoning-absent flow must not emit a placeholder reasoning item into provider history`() {
        // Regression: created -> in_progress -> output_text.delta (no reasoning item ever exists).
        // The transient live-timer placeholder must NOT round-trip into the next request's input
        // as a reasoning item lacking an id — that is an invalid (400-class) Responses item.
        val created = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.created","response":{"id":"resp_1"}}""")
        )!!
        val inProgress = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.in_progress","response":{"id":"resp_1"}}""")
        )!!
        val textDelta = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.output_text.delta","item_id":"msg_1","delta":"answer"}""")
        )!!

        val seed = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val assembled = seed
            .handleMessageChunk(created)
            .handleMessageChunk(inProgress)
            .handleMessageChunk(textDelta)

        // Prepend a user turn so the assistant message is uploaded as history.
        val history = listOf(UIMessage.user("question")) + assembled
        val result = invokeBuildMessages(history)

        val reasoningItems = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }
        assertTrue(
            "no reasoning item must be emitted for a reasoning-absent response",
            reasoningItems.isEmpty()
        )
    }

    @Test
    fun `reasoning-present flow emits exactly one reasoning item carrying its provider id`() {
        // Positive companion: a real reasoning item (output_item.added carries reasoning_id) plus a
        // summary delta must still serialize into exactly one reasoning item with the correct id.
        val created = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.created","response":{"id":"resp_1"}}""")
        )!!
        val itemAdded = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.output_item.added","item":{"type":"reasoning","id":"rs_42"}}""")
        )!!
        val summaryDelta = api.parseResponseDelta(
            parseToJsonObject("""{"type":"response.reasoning_summary_text.delta","item_id":"rs_42","delta":"thinking"}""")
        )!!

        val seed = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val assembled = seed
            .handleMessageChunk(created)
            .handleMessageChunk(itemAdded)
            .handleMessageChunk(summaryDelta)

        val history = listOf(UIMessage.user("question")) + assembled
        val result = invokeBuildMessages(history)

        val reasoningItems = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }
        assertEquals("exactly one reasoning item must be emitted", 1, reasoningItems.size)
        assertEquals(
            "the reasoning item must carry its provider id",
            "rs_42",
            reasoningItems.first().jsonObject["id"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `malformed encrypted_content must not drop the reasoning id on the wire`() {
        // Regression for the per-field metadata reader (ResponseAPI.buildMessages -> openAIReasoningMetadata).
        // Legacy persisted reasoning parts can carry encrypted_content as an OBJECT (not a string).
        // Atomic decode (metadataAs<OpenAIReasoningMetadata>) fails on that sibling field and returns
        // null for the WHOLE object, dropping the valid reasoning_id with it — so buildMessages emits a
        // reasoning item that has lost its provider "id", severing the reasoning chain on the next
        // request. The per-field reader must keep reasoning_id and forward it as "id".
        // (Text is non-blank so isValidToUpload() admits the message and the part reaches buildMessages;
        // the blank-text path is filtered earlier by isValidToUpload and is out of this fix's scope.)
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "thinking",
            metadata = buildJsonObject {
                put("reasoning_id", "rs_x")
                put("encrypted_content", buildJsonObject { put("nested", "v") })
            },
        )
        val history = listOf(
            UIMessage.user("question"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning)),
        )

        val result = invokeBuildMessages(history)

        val reasoningItems = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }
        assertEquals(
            "exactly one reasoning item must be emitted",
            1,
            reasoningItems.size
        )
        assertEquals(
            "the reasoning item must still carry its provider id despite the malformed sibling",
            "rs_x",
            reasoningItems.first().jsonObject["id"]?.jsonPrimitive?.content
        )
        assertFalse(
            "the malformed encrypted_content must be dropped, not forwarded",
            reasoningItems.first().jsonObject.containsKey("encrypted_content")
        )
    }

    @Test
    fun `response api function-only tools should serialize all function entries and omit built-ins`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gpt-response",
                    abilities = listOf(ModelAbility.TOOL)
                ),
                tools = listOf(createFunctionTool("search"), createFunctionTool("calculator"))
            )
        )

        val tools = requestBody["tools"]?.jsonArray
        assertEquals(2, tools?.size)
        val functionNames = functionToolNames(requestBody)
        assertTrue(functionNames.contains("search"))
        assertTrue(functionNames.contains("calculator"))
        assertEquals(0, builtInToolCount(requestBody, "web_search"))
    }

    @Test
    fun `response api builtin-only search should serialize web_search only`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = TextGenerationParams(
                model = Model(modelId = "gpt-response", tools = setOf(BuiltInTools.Search))
            )
        )

        val tools = requestBody["tools"]?.jsonArray
        assertEquals(1, tools?.size)
        assertEquals("web_search", tools?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(0, functionToolCount(requestBody))
    }

    @Test
    fun `response api should not include tools when neither function nor builtin tools exist`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = TextGenerationParams(model = Model(modelId = "gpt-response"))
        )

        assertEquals(null, requestBody["tools"])
    }

    @Test
    fun `response api should combine function tools and builtin web_search in one merged tools array`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gpt-response",
                    abilities = listOf(ModelAbility.TOOL),
                    tools = setOf(BuiltInTools.Search)
                ),
                tools = listOf(createFunctionTool("search"), createFunctionTool("calculator"))
            )
        )

        val tools = requestBody["tools"]?.jsonArray
        assertEquals(3, tools?.size)
        assertEquals(2, functionToolCount(requestBody))
        assertEquals(1, builtInToolCount(requestBody, "web_search"))
        assertTrue(functionToolNames(requestBody).containsAll(listOf("search", "calculator")))
    }

    @Test
    fun `response api should keep function tool names when builtin search is added`() {
        runBlocking {
            checkAll(200, Arb.list(Arb.string(1..8), 1..4)) { names ->
                val functionNames = names.distinct().filter { it.isNotBlank() }
                if (functionNames.isEmpty()) return@checkAll

                val baseRequest = invokeBuildRequestBody(
                    providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
                    params = TextGenerationParams(
                        model = Model(
                            modelId = "gpt-response",
                            abilities = listOf(ModelAbility.TOOL)
                        ),
                        tools = functionNames.map { createFunctionTool(it) }
                    )
                )

                val requestWithBuiltin = invokeBuildRequestBody(
                    providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
                    params = TextGenerationParams(
                        model = Model(
                            modelId = "gpt-response",
                            abilities = listOf(ModelAbility.TOOL),
                            tools = setOf(BuiltInTools.Search)
                        ),
                        tools = functionNames.map { createFunctionTool(it) }
                    )
                )

                assertEquals(
                    functionToolNames(baseRequest).sorted(),
                    functionToolNames(requestWithBuiltin).sorted()
                )
                assertEquals(1, builtInToolCount(requestWithBuiltin, "web_search"))
                assertEquals(
                    functionToolCount(baseRequest) + 1,
                    requestWithBuiltin["tools"]?.jsonArray?.size ?: 0
                )
            }
        }
    }

    // ==================== Helper Functions ====================

    private fun functionToolNames(requestBody: JsonObject): List<String> =
        requestBody["tools"]?.jsonArray
            ?.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function" }
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            ?: emptyList()

    private fun functionToolCount(requestBody: JsonObject): Int =
        requestBody["tools"]?.jsonArray
            ?.count { it.jsonObject["type"]?.jsonPrimitive?.content == "function" }
            ?: 0

    private fun builtInToolCount(requestBody: JsonObject, type: String): Int =
        requestBody["tools"]?.jsonArray
            ?.count { it.jsonObject["type"]?.jsonPrimitive?.content == type }
            ?: 0

    private fun createFunctionTool(name: String): Tool =
        Tool(
            name = name,
            description = "test function tool",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )

    private fun parseToJsonObject(raw: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }

    // ---- C25: an assistant-generated image must not be replayed as a USER input_image ----

    @Test
    fun `assistant generated image is omitted from replay, never re-attributed to user`() {
        val assistantWithImage = UIMessage.assistant("here is your image").copy(
            parts = listOf(
                UIMessagePart.Text("here is your image"),
                UIMessagePart.Image(url = "https://example.invalid/generated.png"),
            ),
        )

        val items = invokeBuildMessages(listOf(assistantWithImage))

        assertFalse(
            "no user-role item may be synthesized from an assistant image",
            items.any { it.jsonObject["role"]?.jsonPrimitive?.content == "user" },
        )
        assertFalse(
            "the assistant image must not be replayed as input_image",
            items.toString().contains("input_image"),
        )
        assertTrue("assistant text is preserved", items.toString().contains("here is your image"))
    }

    @Test
    fun `a normal user message still produces a user item`() {
        val items = invokeBuildMessages(listOf(UIMessage.user("look at this")))

        assertTrue(
            "a normal user message still produces a user item",
            items.any { it.jsonObject["role"]?.jsonPrimitive?.content == "user" },
        )
    }
}
