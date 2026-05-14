package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [groupMessageParts] — the pure helper that splits a message's
 * part list into renderable blocks (consecutive Reasoning/Tool parts collapse
 * into a single ThinkingBlock, everything else becomes a ContentBlock).
 */
class GroupMessagePartsTest {

    private fun text(s: String) = UIMessagePart.Text(s)
    private fun reasoning(s: String) = UIMessagePart.Reasoning(reasoning = s)
    private fun tool(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = "t", input = "{}")

    @Test
    fun `empty list produces no blocks`() {
        assertTrue(emptyList<UIMessagePart>().groupMessageParts().isEmpty())
    }

    @Test
    fun `single text part becomes one content block preserving index`() {
        val result = listOf(text("hello")).groupMessageParts()
        assertEquals(1, result.size)
        val block = result[0] as MessagePartBlock.ContentBlock
        assertEquals(0, block.index)
        assertEquals(text("hello"), block.part)
    }

    @Test
    fun `consecutive reasoning and tool parts collapse into one thinking block`() {
        val result = listOf(
            reasoning("think a"),
            tool("call-1"),
            reasoning("think b"),
        ).groupMessageParts()

        assertEquals(1, result.size)
        val block = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(3, block.steps.size)
        assertTrue(block.steps[0] is ThinkingStep.ReasoningStep)
        assertTrue(block.steps[1] is ThinkingStep.ToolStep)
        assertTrue(block.steps[2] is ThinkingStep.ReasoningStep)
    }

    @Test
    fun `content part flushes pending thinking steps before it`() {
        val result = listOf(
            reasoning("think"),
            tool("call-1"),
            text("answer"),
        ).groupMessageParts()

        assertEquals(2, result.size)
        val thinking = result[0] as MessagePartBlock.ThinkingBlock
        assertEquals(2, thinking.steps.size)
        val content = result[1] as MessagePartBlock.ContentBlock
        assertEquals(text("answer"), content.part)
        // index is the original position in the source list
        assertEquals(2, content.index)
    }

    @Test
    fun `thinking blocks separated by content do not merge`() {
        val result = listOf(
            reasoning("first"),
            text("middle"),
            reasoning("second"),
        ).groupMessageParts()

        assertEquals(3, result.size)
        assertTrue(result[0] is MessagePartBlock.ThinkingBlock)
        assertTrue(result[1] is MessagePartBlock.ContentBlock)
        assertTrue(result[2] is MessagePartBlock.ThinkingBlock)
        assertEquals(1, (result[0] as MessagePartBlock.ThinkingBlock).steps.size)
        assertEquals(1, (result[2] as MessagePartBlock.ThinkingBlock).steps.size)
    }

    @Test
    fun `content block indices reflect original positions across mixed parts`() {
        val result = listOf(
            text("a"),       // index 0
            reasoning("r"),  // grouped
            text("b"),       // index 2
            text("c"),       // index 3
        ).groupMessageParts()

        assertEquals(4, result.size)
        assertEquals(0, (result[0] as MessagePartBlock.ContentBlock).index)
        assertTrue(result[1] is MessagePartBlock.ThinkingBlock)
        assertEquals(2, (result[2] as MessagePartBlock.ContentBlock).index)
        assertEquals(3, (result[3] as MessagePartBlock.ContentBlock).index)
    }

    @Test
    fun `trailing thinking steps are flushed at end of list`() {
        val result = listOf(
            text("intro"),
            reasoning("tail thought"),
        ).groupMessageParts()

        assertEquals(2, result.size)
        assertTrue(result[0] is MessagePartBlock.ContentBlock)
        assertTrue(result[1] is MessagePartBlock.ThinkingBlock)
    }
}
