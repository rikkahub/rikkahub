package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun groupedThinkingStepsPreserveOriginalMessagePartIndices() {
        val blocks = listOf(
            UIMessagePart.Text("prefix"),
            UIMessagePart.Reasoning("thinking"),
            UIMessagePart.Tool(
                toolCallId = "tool-call",
                toolName = "search",
                input = "{}",
            ),
            UIMessagePart.Text("suffix"),
        ).groupMessageParts()

        val steps = blocks.filterIsInstance<MessagePartBlock.ThinkingBlock>().single().steps

        assertEquals(1, (steps[0] as ThinkingStep.ReasoningStep).sourceIndex)
        assertEquals(2, (steps[1] as ThinkingStep.ToolStep).sourceIndex)
    }
}
