package me.rerere.ai.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTest {

    // ==================== limitContext Tests ====================

    @Test
    fun `limitContext with size 0 should return original list`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(0)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with negative size should return original list`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(-1)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with size greater than list size should return original list`() {
        val messages = createTestMessages(3)
        val result = messages.limitContext(5)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with normal size should return last N messages`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(3)
        assertEquals(3, result.size)
        assertEquals(messages.subList(2, 5), result)
    }

    @Test
    fun `limitContext with executed tool at start should include corresponding tool call`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User message"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = emptyList() // Not executed
                    )
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result")) // Executed
                    )
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool call at start should include corresponding user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = emptyList()
                    )
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result"))
                    )
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with empty list should return empty list`() {
        val messages = emptyList<UIMessage>()
        val result = messages.limitContext(5)
        assertEquals(emptyList<UIMessage>(), result)
    }

    @Test
    fun `limitContext with single message should return that message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Single message")))
        )
        val result = messages.limitContext(1)
        assertEquals(1, result.size)
        assertEquals(messages, result)
    }

    // ==================== isValidToUpload Tests ====================

    @Test
    fun `isValidToUpload should be true for non-empty reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking"),
                UIMessagePart.Text("")
            )
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be false for blank reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "   "),
                UIMessagePart.Text("")
            )
        )

        assertFalse(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be true for non-empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("ok"))
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should keep tool-only message valid`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = """{"q":"hello"}"""
                )
            )
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `handleMessageChunk should merge signature-only reasoning delta into existing reasoning`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(
                        reasoning = "thinking",
                        finishedAt = null
                    )
                )
            )
        )

        val chunk = MessageChunk(
            id = "chunk-1",
            model = "claude-test",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                finishedAt = null,
                                metadata = buildJsonObject {
                                    put("signature", "sig-1")
                                }
                            )
                        )
                    ),
                    message = null,
                    finishReason = null
                )
            )
        )

        val result = messages.handleMessageChunk(chunk)
        val reasoning = result.single().parts.single() as UIMessagePart.Reasoning

        assertEquals(1, result.single().parts.size)
        assertEquals("thinking", reasoning.reasoning)
        assertEquals("sig-1", reasoning.metadata?.get("signature")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleMessageChunk should merge signature-only reasoning delta to latest reasoning after text`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(
                        reasoning = "thinking",
                        finishedAt = null
                    ),
                    UIMessagePart.Text("visible text")
                )
            )
        )

        val chunk = MessageChunk(
            id = "chunk-2",
            model = "claude-test",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                finishedAt = null,
                                metadata = buildJsonObject {
                                    put("signature", "sig-2")
                                }
                            )
                        )
                    ),
                    message = null,
                    finishReason = null
                )
            )
        )

        val result = messages.handleMessageChunk(chunk)
        val parts = result.single().parts

        assertEquals(2, parts.size)
        assertTrue(parts[0] is UIMessagePart.Reasoning)
        assertTrue(parts[1] is UIMessagePart.Text)
        assertEquals("sig-2", (parts[0] as UIMessagePart.Reasoning).metadata?.get("signature")?.jsonPrimitive?.content)
        assertEquals("visible text", (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `handleMessageChunk should ignore orphan signature-only reasoning delta`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("visible text"))
            )
        )

        val chunk = MessageChunk(
            id = "chunk-3",
            model = "claude-test",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                finishedAt = null,
                                metadata = buildJsonObject {
                                    put("signature", "sig-3")
                                }
                            )
                        )
                    ),
                    message = null,
                    finishReason = null
                )
            )
        )

        val result = messages.handleMessageChunk(chunk)
        val parts = result.single().parts

        assertEquals(1, parts.size)
        assertTrue(parts.single() is UIMessagePart.Text)
        assertEquals("visible text", (parts.single() as UIMessagePart.Text).text)
    }

    // ==================== migrateToolMessages Tests ====================

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolMessages should convert ToolCall to Tool`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        arguments = """{"arg": "value"}"""
                    )
                )
            )
        )

        val result = messages.migrateToolMessages()

        assertEquals(2, result.size)
        val assistantParts = result[1].parts
        assertEquals(1, assistantParts.size)
        assertTrue(assistantParts[0] is UIMessagePart.Tool)

        val tool = assistantParts[0] as UIMessagePart.Tool
        assertEquals("call1", tool.toolCallId)
        assertEquals("test_tool", tool.toolName)
        assertEquals("""{"arg": "value"}""", tool.input)
        assertTrue(tool.output.isEmpty())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolMessages should merge TOOL message into previous ASSISTANT`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        arguments = "{}"
                    )
                )
            ),
            UIMessage(
                role = MessageRole.TOOL, parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        content = JsonPrimitive("tool output"),
                        arguments = JsonPrimitive("{}")
                    )
                )
            )
        )

        val result = messages.migrateToolMessages()

        // TOOL message should be removed
        assertEquals(2, result.size)
        assertEquals(MessageRole.USER, result[0].role)
        assertEquals(MessageRole.ASSISTANT, result[1].role)

        // Check the Tool part has output
        val tool = result[1].parts[0] as UIMessagePart.Tool
        assertEquals("call1", tool.toolCallId)
        assertTrue(tool.isExecuted)
        assertEquals(1, tool.output.size)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolMessages should handle multiple tool calls and results`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "tool1", "{}"),
                    UIMessagePart.ToolCall("call2", "tool2", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.TOOL, parts = listOf(
                    UIMessagePart.ToolResult("call1", "tool1", JsonPrimitive("result1"), JsonPrimitive("{}")),
                    UIMessagePart.ToolResult("call2", "tool2", JsonPrimitive("result2"), JsonPrimitive("{}"))
                )
            )
        )

        val result = messages.migrateToolMessages()

        assertEquals(2, result.size)
        val tools = result[1].parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertTrue(tools.all { it.isExecuted })
    }

    @Test
    fun `migrateToolMessages should not affect new Tool format`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call1",
                        toolName = "test_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result"))
                    )
                )
            )
        )

        val result = messages.migrateToolMessages()

        assertEquals(messages, result)
    }

    // ==================== migrateToolNodes Tests ====================

    /**
     * Simple data class to simulate MessageNode for testing
     */
    private data class TestNode(
        val id: String,
        val messages: List<UIMessage>,
        val selectIndex: Int = 0
    )

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolNodes should merge TOOL node into previous ASSISTANT node`() {
        val nodes = listOf(
            TestNode(
                id = "node1",
                messages = listOf(
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query")))
                )
            ),
            TestNode(
                id = "node2",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.ToolCall("call1", "test_tool", "{}")
                        )
                    )
                )
            ),
            TestNode(
                id = "node3",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                        )
                    )
                )
            ),
            TestNode(
                id = "node4",
                messages = listOf(
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final")))
                )
            )
        )

        val result = nodes.migrateToolNodes(
            getMessages = { it.messages },
            setMessages = { node, msgs -> node.copy(messages = msgs) }
        )

        // TOOL node should be removed
        assertEquals(3, result.size)
        assertEquals("node1", result[0].id)
        assertEquals("node2", result[1].id)
        assertEquals("node4", result[2].id)

        // Check ASSISTANT node has merged Tool with output
        val assistantMessage = result[1].messages[0]
        val tool = assistantMessage.parts[0] as UIMessagePart.Tool
        assertEquals("call1", tool.toolCallId)
        assertTrue(tool.isExecuted)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolNodes should handle multiple branches in ASSISTANT node`() {
        val nodes = listOf(
            TestNode(
                id = "node1",
                messages = listOf(
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query")))
                )
            ),
            TestNode(
                id = "node2",
                messages = listOf(
                    // Branch 1
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.ToolCall("call1", "tool1", "{}"))
                    ),
                    // Branch 2
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.ToolCall("call2", "tool2", "{}"))
                    )
                ),
                selectIndex = 0
            ),
            TestNode(
                id = "node3",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            UIMessagePart.ToolResult("call1", "tool1", JsonPrimitive("result1"), JsonPrimitive("{}")),
                            UIMessagePart.ToolResult("call2", "tool2", JsonPrimitive("result2"), JsonPrimitive("{}"))
                        )
                    )
                )
            )
        )

        val result = nodes.migrateToolNodes(
            getMessages = { it.messages },
            setMessages = { node, msgs -> node.copy(messages = msgs) }
        )

        assertEquals(2, result.size)

        // Both branches should be migrated
        val assistantNode = result[1]
        assertEquals(2, assistantNode.messages.size)

        // Branch 1 should have call1 result
        val branch1Tool = assistantNode.messages[0].parts[0] as UIMessagePart.Tool
        assertEquals("call1", branch1Tool.toolCallId)
        assertTrue(branch1Tool.isExecuted)

        // Branch 2 should have call2 result
        val branch2Tool = assistantNode.messages[1].parts[0] as UIMessagePart.Tool
        assertEquals("call2", branch2Tool.toolCallId)
        assertTrue(branch2Tool.isExecuted)
    }

    @Test
    fun `migrateToolNodes should not affect nodes without TOOL role`() {
        val nodes = listOf(
            TestNode(
                id = "node1",
                messages = listOf(
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query")))
                )
            ),
            TestNode(
                id = "node2",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool("call1", "tool", "{}", listOf(UIMessagePart.Text("result")))
                        )
                    )
                )
            ),
            TestNode(
                id = "node3",
                messages = listOf(
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final")))
                )
            )
        )

        val result = nodes.migrateToolNodes(
            getMessages = { it.messages },
            setMessages = { node, msgs -> node.copy(messages = msgs) }
        )

        assertEquals(3, result.size)
        assertEquals(nodes.map { it.id }, result.map { it.id })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolNodes should handle TOOL node without preceding ASSISTANT`() {
        val nodes = listOf(
            TestNode(
                id = "node1",
                messages = listOf(
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query")))
                )
            ),
            TestNode(
                id = "node2",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            UIMessagePart.ToolResult("call1", "tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                        )
                    )
                )
            )
        )

        val result = nodes.migrateToolNodes(
            getMessages = { it.messages },
            setMessages = { node, msgs -> node.copy(messages = msgs) }
        )

        // TOOL node should remain since there's no ASSISTANT to merge into
        assertEquals(2, result.size)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolNodes should handle consecutive TOOL nodes`() {
        val nodes = listOf(
            TestNode(
                id = "node1",
                messages = listOf(
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query")))
                )
            ),
            TestNode(
                id = "node2",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.ToolCall("call1", "tool1", "{}"),
                            UIMessagePart.ToolCall("call2", "tool2", "{}")
                        )
                    )
                )
            ),
            TestNode(
                id = "node3",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            UIMessagePart.ToolResult("call1", "tool1", JsonPrimitive("result1"), JsonPrimitive("{}"))
                        )
                    )
                )
            ),
            TestNode(
                id = "node4",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            UIMessagePart.ToolResult("call2", "tool2", JsonPrimitive("result2"), JsonPrimitive("{}"))
                        )
                    )
                )
            )
        )

        val result = nodes.migrateToolNodes(
            getMessages = { it.messages },
            setMessages = { node, msgs -> node.copy(messages = msgs) }
        )

        // Both TOOL nodes should be merged
        assertEquals(2, result.size)
        assertEquals("node1", result[0].id)
        assertEquals("node2", result[1].id)

        val tools = result[1].messages[0].parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertTrue(tools.all { it.isExecuted })
    }

    // ==================== Parts Sorting Tests ====================

    @Test
    fun `migrateToolMessages should sort parts by priority - Reasoning before Text`() {
        // Create message with wrong order: Text before Reasoning
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Response text"),
                    UIMessagePart.Reasoning(reasoning = "Thinking process")
                )
            )
        )

        val result = messages.migrateToolMessages()

        assertEquals(2, result.size)
        val assistantParts = result[1].parts
        assertEquals(2, assistantParts.size)
        // Reasoning (priority=-1) should come before Text (priority=0)
        assertTrue(assistantParts[0] is UIMessagePart.Reasoning)
        assertTrue(assistantParts[1] is UIMessagePart.Text)
    }

    @Test
    fun `migrateToolMessages should sort parts with Tool and Reasoning`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool("call1", "tool", "{}", listOf(UIMessagePart.Text("result"))),
                    UIMessagePart.Text("Response"),
                    UIMessagePart.Reasoning(reasoning = "Thinking")
                )
            )
        )

        val result = messages.migrateToolMessages()

        val assistantParts = result[1].parts
        assertEquals(3, assistantParts.size)
        // Order should be: Reasoning(-1), Tool(0), Text(0) - stable sort keeps Tool before Text
        assertTrue(assistantParts[0] is UIMessagePart.Reasoning)
        // Tool and Text both have priority 0, order among them is stable
    }

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolMessages should sort parts after merging ToolResult`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Let me think"),
                    UIMessagePart.Reasoning(reasoning = "Thinking"),
                    UIMessagePart.ToolCall("call1", "tool", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult("call1", "tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            )
        )

        val result = messages.migrateToolMessages()

        assertEquals(2, result.size)
        val assistantParts = result[1].parts
        assertEquals(3, assistantParts.size)
        // Reasoning should be first (priority=-1)
        assertTrue(assistantParts[0] is UIMessagePart.Reasoning)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `migrateToolNodes should sort parts after merging`() {
        val nodes = listOf(
            TestNode(
                id = "node1",
                messages = listOf(
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query")))
                )
            ),
            TestNode(
                id = "node2",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Text("Response"),
                            UIMessagePart.Reasoning(reasoning = "Thinking"),
                            UIMessagePart.ToolCall("call1", "tool", "{}")
                        )
                    )
                )
            ),
            TestNode(
                id = "node3",
                messages = listOf(
                    UIMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            UIMessagePart.ToolResult("call1", "tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                        )
                    )
                )
            )
        )

        val result = nodes.migrateToolNodes(
            getMessages = { it.messages },
            setMessages = { node, msgs -> node.copy(messages = msgs) }
        )

        assertEquals(2, result.size)
        val assistantParts = result[1].messages[0].parts
        assertEquals(3, assistantParts.size)
        // Reasoning should be first (priority=-1)
        assertTrue(assistantParts[0] is UIMessagePart.Reasoning)
    }

    @Test
    fun `migrateToolMessages should handle Image parts with correct priority`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Query"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image(url = "http://example.com/image.png"),
                    UIMessagePart.Text("Description"),
                    UIMessagePart.Reasoning(reasoning = "Thinking")
                )
            )
        )

        val result = messages.migrateToolMessages()

        val assistantParts = result[1].parts
        assertEquals(3, assistantParts.size)
        // Order: Reasoning(-1), Text(0), Image(1)
        assertTrue(assistantParts[0] is UIMessagePart.Reasoning)
        assertTrue(assistantParts[1] is UIMessagePart.Text)
        assertTrue(assistantParts[2] is UIMessagePart.Image)
    }

    // ==================== Helper Functions ====================

    private fun createTestMessages(count: Int): List<UIMessage> {
        return (0 until count).map { i ->
            UIMessage(
                role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Message $i"))
            )
        }
    }
}
