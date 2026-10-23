package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationPhaseTest {
    private fun phaseOf(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()).generationPhase()

    @Test
    fun `unexecuted tool wins and drops the MCP server prefix`() {
        val phase = phaseOf(
            UIMessagePart.Text("let me check"),
            UIMessagePart.Tool(toolCallId = "1", toolName = "server__search", input = "{\"q\":1}"),
        )
        assertEquals(GenerationPhase.RunningTool(toolName = "search", input = "{\"q\":1}"), phase)
    }

    @Test
    fun `unfinished reasoning is thinking`() {
        val phase = phaseOf(UIMessagePart.Reasoning(reasoning = "hmm", finishedAt = null))
        assertEquals(GenerationPhase.Thinking("hmm"), phase)
    }

    @Test
    fun `text after finished reasoning and executed tool is writing`() {
        val phase = phaseOf(
            UIMessagePart.Reasoning(reasoning = "done"),
            UIMessagePart.Tool(
                toolCallId = "1",
                toolName = "search",
                input = "{}",
                output = listOf(UIMessagePart.Text("result")),
            ),
            UIMessagePart.Text("answer"),
        )
        assertEquals(GenerationPhase.Writing("answer"), phase)
    }

    @Test
    fun `no content yet is waiting`() {
        assertEquals(GenerationPhase.Waiting, phaseOf())
    }
}
