package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Clock

class TransformNewPartsTest {
    private fun reply(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun UIMessage.texts() = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    @Test
    fun `parts from earlier steps are not transformed again`() = runTest {
        val message = reply(
            UIMessagePart.Text("step one"),
            UIMessagePart.Tool(toolCallId = "call_1", toolName = "search", input = "{}"),
            UIMessagePart.Text("step two"),
        )

        val transformed = message.transformNewParts(newPartsFrom = 2) { messages ->
            messages.map { it.copy(parts = it.parts.map { part -> (part as UIMessagePart.Text).copy(text = part.text + "!") }) }
        }

        assertEquals(listOf("step one", "step two!"), transformed.texts())
        assertEquals(message.id, transformed.id)
    }

    @Test
    fun `think tags at the start of a later step are recognized`() = runTest {
        val message = reply(
            UIMessagePart.Reasoning(reasoning = "first thoughts"),
            UIMessagePart.Text("checking"),
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "search",
                input = "{}",
                output = listOf(UIMessagePart.Text("result")),
            ),
            UIMessagePart.Text("<think>second thoughts</think>answer"),
        )

        val transformed = message.transformNewParts(newPartsFrom = 3) {
            it.transformThinkTags(now = Clock.System.now(), generationFinished = true)
        }

        assertEquals(
            listOf("first thoughts", "second thoughts"),
            transformed.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning },
        )
        assertEquals(listOf("checking", "answer"), transformed.texts())
    }
}
