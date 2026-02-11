package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 */
class ChatCompletionsAPIMessageTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private buildMessages method via reflection
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(api, messages) as JsonArray
    }

    @Test
    fun `multi-round reasoning and tool calls should be correctly ordered`() {
        // Scenario: Assistant message with multiple rounds of reasoning and tool calls
        // [Reasoning1, Text1, Tool1(executed), Reasoning2, Text2, Tool2(executed), Text3]
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("I'll search for information"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result 1"),
                UIMessagePart.Reasoning(reasoning = "Now I need to calculate..."),
                UIMessagePart.Text("Let me calculate that"),
                createExecutedTool("call_2", "calculate", """{"expr": "1+1"}""", "2"),
                UIMessagePart.Text("The final answer is 2")
            )
        )

        val messages = listOf(
            UIMessage.user("What is 1+1?"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Result should contain:
        // 1. User message
        // 2. Assistant message with reasoning_content, content, and tool_calls for search
        // 3. Tool result for search
        // 4. Assistant message with reasoning_content, content, and tool_calls for calculate
        // 5. Tool result for calculate
        // 6. Assistant message with final text

        assertTrue("Should have at least 6 messages", result.size >= 6)

        // Verify user message
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        // Verify first assistant message (with first tool call)
        val assistant1 = result[1].jsonObject
        assertEquals("assistant", assistant1["role"]?.jsonPrimitive?.content)
        assertTrue("First assistant message should have tool_calls", assistant1.containsKey("tool_calls"))
        val toolCalls1 = assistant1["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls1?.size)
        assertEquals("search", toolCalls1?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify first tool result
        val toolResult1 = result[2].jsonObject
        assertEquals("tool", toolResult1["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", toolResult1["tool_call_id"]?.jsonPrimitive?.content)

        // Verify second assistant message (with second tool call)
        val assistant2 = result[3].jsonObject
        assertEquals("assistant", assistant2["role"]?.jsonPrimitive?.content)
        assertTrue("Second assistant message should have tool_calls", assistant2.containsKey("tool_calls"))
        val toolCalls2 = assistant2["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls2?.size)
        assertEquals("calculate", toolCalls2?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify second tool result
        val toolResult2 = result[4].jsonObject
        assertEquals("tool", toolResult2["role"]?.jsonPrimitive?.content)
        assertEquals("call_2", toolResult2["tool_call_id"]?.jsonPrimitive?.content)

        // Verify final assistant message
        val assistant3 = result[5].jsonObject
        assertEquals("assistant", assistant3["role"]?.jsonPrimitive?.content)
        val content = assistant3["content"]
        assertTrue("Final assistant content should contain 'final answer'",
            content?.jsonPrimitive?.content?.contains("final answer") == true ||
            (content is JsonArray && content.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("final answer") == true })
        )
    }

    @Test
    fun `parallel tool calls should be grouped together`() {
        // Scenario: Multiple tools called in parallel
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search multiple sources"),
                createExecutedTool("call_1", "search_web", """{"query": "test1"}""", "Result 1"),
                createExecutedTool("call_2", "search_docs", """{"query": "test2"}""", "Result 2"),
                createExecutedTool("call_3", "search_wiki", """{"query": "test3"}""", "Result 3"),
                UIMessagePart.Text("Combined results show...")
            )
        )

        val messages = listOf(
            UIMessage.user("Search everything"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify parallel tools are in same assistant message
        var foundAssistantWithMultipleTools = false
        for (element in result) {
            val msg = element.jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant") {
                val toolCalls = msg["tool_calls"]?.jsonArray
                if (toolCalls != null && toolCalls.size == 3) {
                    foundAssistantWithMultipleTools = true
                    // Verify all three tool calls are present
                    val toolNames = toolCalls.map {
                        it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    }
                    assertTrue(toolNames.contains("search_web"))
                    assertTrue(toolNames.contains("search_docs"))
                    assertTrue(toolNames.contains("search_wiki"))
                    break
                }
            }
        }
        assertTrue("Should have assistant message with 3 parallel tool calls", foundAssistantWithMultipleTools)

        // Verify 3 separate tool result messages
        val toolResults = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
        }
        assertEquals(3, toolResults.size)
    }

    @Test
    fun `reasoning should only be included for messages after last user message`() {
        // First assistant message (before user's last message) - reasoning should NOT be included
        val assistant1 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Initial thinking"),
                UIMessagePart.Text("Initial response")
            )
        )

        // Second assistant message (after user's last message) - reasoning SHOULD be included
        val assistant2 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Final thinking"),
                UIMessagePart.Text("Final response")
            )
        )

        val messages = listOf(
            UIMessage.user("First question"),
            assistant1,
            UIMessage.user("Second question"),
            assistant2
        )

        val result = invokeBuildMessages(messages)

        // Find assistant messages
        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)

        // First assistant should NOT have reasoning_content
        val first = assistantMessages[0].jsonObject
        assertTrue("First assistant should not have reasoning_content",
            !first.containsKey("reasoning_content") ||
            first["reasoning_content"]?.jsonPrimitive?.content.isNullOrEmpty()
        )

        // Second assistant SHOULD have reasoning_content
        val second = assistantMessages[1].jsonObject
        assertTrue("Second assistant should have reasoning_content",
            second.containsKey("reasoning_content") &&
            second["reasoning_content"]?.jsonPrimitive?.content?.isNotEmpty() == true
        )
    }

    @Test
    fun `tool_call followed by tool result should maintain correct order`() {
        // Verify the pattern: assistant (with tool_calls) -> tool (result)
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Calling tool"),
                createExecutedTool("call_abc", "my_tool", """{"param": "value"}""", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find the assistant message with tool_calls
        var assistantIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assistantIndex = i
                break
            }
        }

        assertTrue("Should find assistant with tool_calls", assistantIndex >= 0)

        // The next message should be the tool result
        val nextMsg = result[assistantIndex + 1].jsonObject
        assertEquals("tool", nextMsg["role"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("my_tool", nextMsg["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `complex multi-round conversation with interleaved reasoning and tools`() {
        // Complex scenario simulating agent conversation
        val messages = listOf(
            UIMessage.user("Plan and execute a task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Step 1: Analyze the task"),
                    UIMessagePart.Text("First, I'll gather information"),
                    createExecutedTool("call_1", "gather_info", "{}", "Info gathered"),
                    UIMessagePart.Reasoning(reasoning = "Step 2: Process the information"),
                    UIMessagePart.Text("Now processing..."),
                    createExecutedTool("call_2", "process", "{}", "Processed"),
                    UIMessagePart.Reasoning(reasoning = "Step 3: Generate output"),
                    UIMessagePart.Text("Here is the result")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Verify structure:
        // 1. user message
        // 2. assistant (reasoning + text + tool_calls)
        // 3. tool result
        // 4. assistant (reasoning + text + tool_calls)
        // 5. tool result
        // 6. assistant (reasoning + text)

        // Count message types
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val assistantCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        val toolCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }

        assertEquals("Should have 1 user message", 1, userCount)
        assertEquals("Should have 2 tool results", 2, toolCount)
        assertTrue("Should have at least 3 assistant messages", assistantCount >= 3)

        // Verify order: each tool_calls should be immediately followed by tool result
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assertTrue("Index should not be last", i < result.size - 1)
                val nextMsg = result[i + 1].jsonObject
                assertEquals("Tool result should follow tool_calls",
                    "tool", nextMsg["role"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `assistant with only reasoning and empty text should be filtered out`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 1", result[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 2", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `latest assistant with reasoning and empty text should keep reasoning content`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("", result[1].jsonObject["content"]?.jsonPrimitive?.content)
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
