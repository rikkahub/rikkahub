package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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

    // Helper to invoke private buildMessages method via reflection
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        val method = ResponseAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(api, messages) as JsonArray
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

    // ==================== Helper Functions ====================

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
}
