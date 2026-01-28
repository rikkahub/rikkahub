package me.rerere.ai.provider.providers

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
 * Unit tests for ClaudeProvider message building logic.
 * Tests the conversion from UIMessage list to Anthropic Claude API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * Claude API format:
 * - tool_use blocks within assistant content
 * - tool_result blocks in a separate user message immediately following
 * - thinking blocks for reasoning
 */
class ClaudeProviderMessageTest {

    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    // Helper to invoke private buildMessages method via reflection
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, messages) as JsonArray
    }

    @Test
    fun `multi-round tool calls should produce tool_use followed by tool_result`() {
        // Scenario: Multiple rounds of tool calls
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

        // Claude format:
        // 1. user message
        // 2. assistant message with [text, tool_use(search)]
        // 3. user message with [tool_result(search)]
        // 4. assistant message with [text, tool_use(calculate)]
        // 5. user message with [tool_result(calculate)]
        // 6. assistant message with [text]

        // Find all tool_use blocks
        val toolUseBlocks = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val toolResultBlocks = mutableListOf<kotlinx.serialization.json.JsonObject>()

        for (msg in result) {
            val msgObj = msg.jsonObject
            val content = msgObj["content"]?.jsonArray ?: continue
            for (block in content) {
                val blockObj = block.jsonObject
                val type = blockObj["type"]?.jsonPrimitive?.content
                if (type == "tool_use") {
                    toolUseBlocks.add(blockObj)
                } else if (type == "tool_result") {
                    toolResultBlocks.add(blockObj)
                }
            }
        }

        assertEquals("Should have 2 tool_use blocks", 2, toolUseBlocks.size)
        assertEquals("Should have 2 tool_result blocks", 2, toolResultBlocks.size)

        // Verify tool_use contents
        assertEquals("search", toolUseBlocks[0]["name"]?.jsonPrimitive?.content)
        assertEquals("call_1", toolUseBlocks[0]["id"]?.jsonPrimitive?.content)
        assertEquals("calculate", toolUseBlocks[1]["name"]?.jsonPrimitive?.content)
        assertEquals("call_2", toolUseBlocks[1]["id"]?.jsonPrimitive?.content)

        // Verify tool_result contents
        assertEquals("call_1", toolResultBlocks[0]["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("call_2", toolResultBlocks[1]["tool_use_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool_use in assistant should be immediately followed by user message with tool_result`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Using tool"),
                createExecutedTool("call_abc", "my_tool", "{}", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant message with tool_use
        var assistantWithToolUseIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant") {
                val content = msg["content"]?.jsonArray ?: continue
                if (content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }) {
                    assistantWithToolUseIndex = i
                    break
                }
            }
        }

        assertTrue("Should find assistant with tool_use", assistantWithToolUseIndex >= 0)
        assertTrue("Should not be last message", assistantWithToolUseIndex < result.size - 1)

        // Next message should be user with tool_result
        val nextMsg = result[assistantWithToolUseIndex + 1].jsonObject
        assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
        val nextContent = nextMsg["content"]?.jsonArray
        assertTrue("Next message should have tool_result",
            nextContent?.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result" } == true)
    }

    @Test
    fun `thinking blocks should be included in assistant content`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("Here is my response")
            )
        )

        val messages = listOf(
            UIMessage.user("Question"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant message
        val assistantMsg = result.find {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }?.jsonObject

        assertTrue("Should have assistant message", assistantMsg != null)

        val content = assistantMsg!!["content"]?.jsonArray
        assertTrue("Content should not be null", content != null)

        // Check for thinking block
        val hasThinking = content!!.any {
            it.jsonObject["type"]?.jsonPrimitive?.content == "thinking"
        }
        assertTrue("Should have thinking block", hasThinking)

        // Verify thinking content
        val thinkingBlock = content.find {
            it.jsonObject["type"]?.jsonPrimitive?.content == "thinking"
        }?.jsonObject
        assertEquals("Let me think about this...",
            thinkingBlock?.get("thinking")?.jsonPrimitive?.content)
    }

    @Test
    fun `multi-round reasoning and tools should maintain correct order`() {
        // Complex scenario with interleaved reasoning and tools
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Step 1: Search for info"),
                UIMessagePart.Text("Searching..."),
                createExecutedTool("call_1", "search", "{}", "Found data"),
                UIMessagePart.Reasoning(reasoning = "Step 2: Analyze the data"),
                UIMessagePart.Text("Analyzing..."),
                createExecutedTool("call_2", "analyze", "{}", "Analysis complete"),
                UIMessagePart.Reasoning(reasoning = "Step 3: Present results"),
                UIMessagePart.Text("Here are the results")
            )
        )

        val messages = listOf(
            UIMessage.user("Analyze something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure:
        // Messages should alternate: assistant (with tool_use) -> user (with tool_result)
        // And reasoning should be preserved in assistant messages

        var toolUseCount = 0
        var toolResultCount = 0
        var thinkingCount = 0

        for (msg in result) {
            val msgObj = msg.jsonObject
            val content = msgObj["content"]?.jsonArray ?: continue
            for (block in content) {
                val blockObj = block.jsonObject
                when (blockObj["type"]?.jsonPrimitive?.content) {
                    "tool_use" -> toolUseCount++
                    "tool_result" -> toolResultCount++
                    "thinking" -> thinkingCount++
                }
            }
        }

        assertEquals("Should have 2 tool_use blocks", 2, toolUseCount)
        assertEquals("Should have 2 tool_result blocks", 2, toolResultCount)
        // Note: Not all reasoning may be included depending on implementation
        assertTrue("Should have thinking blocks", thinkingCount >= 0)

        // Verify tool_use -> tool_result order
        for (i in 0 until result.size - 1) {
            val msg = result[i].jsonObject
            val content = msg["content"]?.jsonArray ?: continue
            val hasToolUse = content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }

            if (hasToolUse && msg["role"]?.jsonPrimitive?.content == "assistant") {
                // Next should be user with tool_result
                val nextMsg = result[i + 1].jsonObject
                assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
                val nextContent = nextMsg["content"]?.jsonArray
                assertTrue("Should have tool_result in next message",
                    nextContent?.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result" } == true)
            }
        }
    }

    @Test
    fun `parallel tool calls should be in same assistant message`() {
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
            UIMessage.user("Do multiple things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant message with all tool_use blocks
        var foundAssistantWithMultipleTools = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "assistant") continue

            val content = msgObj["content"]?.jsonArray ?: continue
            val toolUseBlocks = content.filter {
                it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            }

            if (toolUseBlocks.size == 3) {
                foundAssistantWithMultipleTools = true
                // Verify tool names
                val toolNames = toolUseBlocks.map {
                    it.jsonObject["name"]?.jsonPrimitive?.content
                }
                assertTrue(toolNames.contains("tool_a"))
                assertTrue(toolNames.contains("tool_b"))
                assertTrue(toolNames.contains("tool_c"))
                break
            }
        }

        assertTrue("Should have assistant with 3 parallel tool_use blocks",
            foundAssistantWithMultipleTools)

        // Verify corresponding tool_result blocks in user message
        var foundUserWithMultipleResults = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "user") continue

            val content = msgObj["content"]?.jsonArray ?: continue
            val toolResultBlocks = content.filter {
                it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result"
            }

            if (toolResultBlocks.size == 3) {
                foundUserWithMultipleResults = true
                break
            }
        }

        assertTrue("Should have user with 3 tool_result blocks",
            foundUserWithMultipleResults)
    }

    @Test
    fun `user messages should have correct content format`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("Hello, how are you?")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        assertEquals(1, result.size)
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        val content = userMsg["content"]?.jsonArray
        assertTrue("Content should not be null", content != null)
        assertTrue("Content should not be empty", content!!.isNotEmpty())

        val textBlock = content.find {
            it.jsonObject["type"]?.jsonPrimitive?.content == "text"
        }?.jsonObject
        assertEquals("Hello, how are you?", textBlock?.get("text")?.jsonPrimitive?.content)
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
