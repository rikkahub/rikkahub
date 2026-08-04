package me.rerere.ai.provider.providers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChunkNormalizerTest {
    @Test
    fun `switching from reasoning to text should emit explicit boundaries`() {
        val normalizer = StreamChunkNormalizer()

        val reasoning = normalizer.append(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning("thinking")),
            ),
            "response-1",
        )
        val text = normalizer.append(UIMessage.assistant("answer"), "response-1")
        val finish = normalizer.finish("stop", "response-1", "model-1")

        assertTrue(reasoning[0] is StreamChunk.ReasoningStart)
        assertEquals("thinking", (reasoning[1] as StreamChunk.ReasoningDelta).text)
        assertTrue(text[0] is StreamChunk.ReasoningEnd)
        assertTrue(text[1] is StreamChunk.TextStart)
        assertEquals("answer", (text[2] as StreamChunk.TextDelta).text)
        assertTrue(finish[0] is StreamChunk.TextEnd)
        assertEquals("stop", (finish[1] as StreamChunk.Finish).finishReason)
    }

    @Test
    fun `blank tool ids should continue the active tool call`() {
        val normalizer = StreamChunkNormalizer()

        val start = normalizer.append(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool("call-1", "search", "", emptyList())),
            )
        )
        val delta = normalizer.append(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool("", "", "{\"q\":", emptyList())),
            )
        )
        val end = normalizer.closeTool("call-1")

        assertEquals("search", (start.single() as StreamChunk.ToolCallStart).toolName)
        assertEquals("call-1", (delta.single() as StreamChunk.ToolCallDelta).id)
        assertEquals("{\"q\":", (delta.single() as StreamChunk.ToolCallDelta).inputDelta)
        assertTrue(end.single() is StreamChunk.ToolCallEnd)
    }
}
