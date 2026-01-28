package me.rerere.ai.provider.providers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class ProviderMessageUtilsTest {

    // ==================== groupPartsByToolBoundary Tests ====================

    @Test
    fun `empty parts should return empty groups`() {
        val parts = emptyList<UIMessagePart>()
        val result = groupPartsByToolBoundary(parts)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `only text parts should return single Content group`() {
        val parts = listOf(
            UIMessagePart.Text("Hello"),
            UIMessagePart.Text("World")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(1, result.size)
        assertTrue(result[0] is PartGroup.Content)
        assertEquals(2, (result[0] as PartGroup.Content).parts.size)
    }

    @Test
    fun `only executed tools should return single Tools group`() {
        val parts = listOf(
            createExecutedTool("call1", "tool1"),
            createExecutedTool("call2", "tool2")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(1, result.size)
        assertTrue(result[0] is PartGroup.Tools)
        assertEquals(2, (result[0] as PartGroup.Tools).tools.size)
    }

    @Test
    fun `unexecuted tool should be in Content group`() {
        val parts = listOf(
            UIMessagePart.Text("Hello"),
            createUnexecutedTool("call1", "tool1")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(1, result.size)
        assertTrue(result[0] is PartGroup.Content)
        assertEquals(2, (result[0] as PartGroup.Content).parts.size)
    }

    @Test
    fun `text then tool should create Content then Tools groups`() {
        val parts = listOf(
            UIMessagePart.Text("Hello"),
            createExecutedTool("call1", "tool1")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(2, result.size)
        assertTrue(result[0] is PartGroup.Content)
        assertTrue(result[1] is PartGroup.Tools)
    }

    @Test
    fun `tool then text should create Tools then Content groups`() {
        val parts = listOf(
            createExecutedTool("call1", "tool1"),
            UIMessagePart.Text("Result")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(2, result.size)
        assertTrue(result[0] is PartGroup.Tools)
        assertTrue(result[1] is PartGroup.Content)
    }

    @Test
    fun `interleaved text and tools should create alternating groups`() {
        // [Text1, Tool1, Tool2, Text2, Tool3]
        val parts = listOf(
            UIMessagePart.Text("Text1"),
            createExecutedTool("call1", "tool1"),
            createExecutedTool("call2", "tool2"),
            UIMessagePart.Text("Text2"),
            createExecutedTool("call3", "tool3")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(4, result.size)

        // Content([Text1])
        assertTrue(result[0] is PartGroup.Content)
        assertEquals(1, (result[0] as PartGroup.Content).parts.size)

        // Tools([Tool1, Tool2])
        assertTrue(result[1] is PartGroup.Tools)
        assertEquals(2, (result[1] as PartGroup.Tools).tools.size)

        // Content([Text2])
        assertTrue(result[2] is PartGroup.Content)
        assertEquals(1, (result[2] as PartGroup.Content).parts.size)

        // Tools([Tool3])
        assertTrue(result[3] is PartGroup.Tools)
        assertEquals(1, (result[3] as PartGroup.Tools).tools.size)
    }

    @Test
    fun `reasoning should be grouped with content`() {
        val parts = listOf(
            UIMessagePart.Reasoning(reasoning = "Thinking..."),
            UIMessagePart.Text("Response"),
            createExecutedTool("call1", "tool1")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(2, result.size)

        // Content([Reasoning, Text])
        assertTrue(result[0] is PartGroup.Content)
        val contentParts = (result[0] as PartGroup.Content).parts
        assertEquals(2, contentParts.size)
        assertTrue(contentParts[0] is UIMessagePart.Reasoning)
        assertTrue(contentParts[1] is UIMessagePart.Text)

        // Tools([Tool1])
        assertTrue(result[1] is PartGroup.Tools)
    }

    @Test
    fun `tool then reasoning then text should preserve order`() {
        // [Tool(executed), Reasoning, Text]
        // -> [Tools([Tool]), Content([Reasoning, Text])]
        val parts = listOf(
            createExecutedTool("call1", "tool1"),
            UIMessagePart.Reasoning(reasoning = "Thinking after tool"),
            UIMessagePart.Text("Final response")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(2, result.size)

        // Tools([Tool1])
        assertTrue(result[0] is PartGroup.Tools)
        assertEquals(1, (result[0] as PartGroup.Tools).tools.size)

        // Content([Reasoning, Text])
        assertTrue(result[1] is PartGroup.Content)
        val contentParts = (result[1] as PartGroup.Content).parts
        assertEquals(2, contentParts.size)
        assertTrue(contentParts[0] is UIMessagePart.Reasoning)
        assertTrue(contentParts[1] is UIMessagePart.Text)
    }

    @Test
    fun `complex multi-round tool call scenario`() {
        // Simulate: thinking -> text -> tool1 -> reasoning -> text -> tool2 -> final text
        val parts = listOf(
            UIMessagePart.Reasoning(reasoning = "Initial thinking"),
            UIMessagePart.Text("Let me search"),
            createExecutedTool("call1", "search"),
            UIMessagePart.Reasoning(reasoning = "Analyzing results"),
            UIMessagePart.Text("Now calculating"),
            createExecutedTool("call2", "calculate"),
            UIMessagePart.Text("Final answer")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(5, result.size)

        // Group 0: Content([Reasoning, Text])
        assertTrue(result[0] is PartGroup.Content)
        assertEquals(2, (result[0] as PartGroup.Content).parts.size)

        // Group 1: Tools([search])
        assertTrue(result[1] is PartGroup.Tools)
        assertEquals("search", (result[1] as PartGroup.Tools).tools[0].toolName)

        // Group 2: Content([Reasoning, Text])
        assertTrue(result[2] is PartGroup.Content)
        assertEquals(2, (result[2] as PartGroup.Content).parts.size)

        // Group 3: Tools([calculate])
        assertTrue(result[3] is PartGroup.Tools)
        assertEquals("calculate", (result[3] as PartGroup.Tools).tools[0].toolName)

        // Group 4: Content([Text])
        assertTrue(result[4] is PartGroup.Content)
        assertEquals(1, (result[4] as PartGroup.Content).parts.size)
    }

    @Test
    fun `parallel tool calls should stay in same Tools group`() {
        // [Tool1, Tool2, Tool3] (all executed together)
        val parts = listOf(
            createExecutedTool("call1", "tool1"),
            createExecutedTool("call2", "tool2"),
            createExecutedTool("call3", "tool3")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(1, result.size)
        assertTrue(result[0] is PartGroup.Tools)
        assertEquals(3, (result[0] as PartGroup.Tools).tools.size)
    }

    @Test
    fun `image parts should be grouped with content`() {
        val parts = listOf(
            UIMessagePart.Image(url = "http://example.com/image.png"),
            UIMessagePart.Text("Description"),
            createExecutedTool("call1", "tool1")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(2, result.size)

        // Content([Image, Text])
        assertTrue(result[0] is PartGroup.Content)
        val contentParts = (result[0] as PartGroup.Content).parts
        assertEquals(2, contentParts.size)
        assertTrue(contentParts[0] is UIMessagePart.Image)
        assertTrue(contentParts[1] is UIMessagePart.Text)
    }

    // ==================== Message Order Verification Tests ====================

    @Test
    fun `verify tool_use followed by tool_result pattern for Claude`() {
        // For Claude API: tool_use must be immediately followed by tool_result
        // Input: [Text, Tool(executed), MoreText]
        // Expected output structure:
        //   assistant: [text, tool_use]
        //   user: [tool_result]
        //   assistant: [more_text]

        val parts = listOf(
            UIMessagePart.Text("Let me help"),
            createExecutedTool("call1", "search"),
            UIMessagePart.Text("Based on the results")
        )
        val groups = groupPartsByToolBoundary(parts)

        // Verify the grouping supports correct output
        assertEquals(3, groups.size)

        // First: Content with text
        assertTrue(groups[0] is PartGroup.Content)
        assertEquals(1, (groups[0] as PartGroup.Content).parts.size)

        // Second: Tools (will become tool_use, then tool_result)
        assertTrue(groups[1] is PartGroup.Tools)

        // Third: Content with remaining text (will be new assistant message)
        assertTrue(groups[2] is PartGroup.Content)
    }

    @Test
    fun `verify reasoning stays with correct content group`() {
        // Reasoning should stay with the content that follows it, not jump around
        // Input: [Tool(executed), Reasoning, Text]
        // The Reasoning and Text should be in the same Content group AFTER the Tool

        val parts = listOf(
            createExecutedTool("call1", "tool1"),
            UIMessagePart.Reasoning(reasoning = "Thinking about results"),
            UIMessagePart.Text("Here's what I found")
        )
        val groups = groupPartsByToolBoundary(parts)

        assertEquals(2, groups.size)

        // First: Tools
        assertTrue(groups[0] is PartGroup.Tools)

        // Second: Content with Reasoning and Text (in order)
        assertTrue(groups[1] is PartGroup.Content)
        val contentParts = (groups[1] as PartGroup.Content).parts
        assertEquals(2, contentParts.size)
        assertTrue(contentParts[0] is UIMessagePart.Reasoning)
        assertTrue(contentParts[1] is UIMessagePart.Text)
    }

    @Test
    fun `multi-round reasoning should be preserved in correct positions`() {
        // Input: [Reasoning1, Text1, Tool1, Reasoning2, Text2, Tool2, Reasoning3, Text3]
        // Each Reasoning should stay with its following content

        val parts = listOf(
            UIMessagePart.Reasoning(reasoning = "First thought"),
            UIMessagePart.Text("First action"),
            createExecutedTool("call1", "tool1"),
            UIMessagePart.Reasoning(reasoning = "Second thought"),
            UIMessagePart.Text("Second action"),
            createExecutedTool("call2", "tool2"),
            UIMessagePart.Reasoning(reasoning = "Final thought"),
            UIMessagePart.Text("Final answer")
        )
        val groups = groupPartsByToolBoundary(parts)

        assertEquals(5, groups.size)

        // Group 0: [Reasoning1, Text1]
        assertTrue(groups[0] is PartGroup.Content)
        var content = (groups[0] as PartGroup.Content).parts
        assertEquals(2, content.size)
        assertEquals("First thought", (content[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("First action", (content[1] as UIMessagePart.Text).text)

        // Group 1: [Tool1]
        assertTrue(groups[1] is PartGroup.Tools)

        // Group 2: [Reasoning2, Text2]
        assertTrue(groups[2] is PartGroup.Content)
        content = (groups[2] as PartGroup.Content).parts
        assertEquals(2, content.size)
        assertEquals("Second thought", (content[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Second action", (content[1] as UIMessagePart.Text).text)

        // Group 3: [Tool2]
        assertTrue(groups[3] is PartGroup.Tools)

        // Group 4: [Reasoning3, Text3]
        assertTrue(groups[4] is PartGroup.Content)
        content = (groups[4] as PartGroup.Content).parts
        assertEquals(2, content.size)
        assertEquals("Final thought", (content[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Final answer", (content[1] as UIMessagePart.Text).text)
    }

    // ==================== Helper Functions ====================

    private fun createExecutedTool(callId: String, name: String): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = "{}",
            output = listOf(UIMessagePart.Text("Result from $name"))
        )
    }

    private fun createUnexecutedTool(callId: String, name: String): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = "{}",
            output = emptyList()
        )
    }
}
