package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StreamChunkTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `text lifecycle should create and update assistant message`() {
        var messages = listOf(UIMessage.user("hello"))

        messages = messages.handleStreamChunk(StreamChunk.TextStart("text-1"), model)
        messages = messages.handleStreamChunk(StreamChunk.TextDelta("text-1", "hel"), model)
        messages = messages.handleStreamChunk(StreamChunk.TextDelta("text-1", "lo"), model)
        messages = messages.handleStreamChunk(StreamChunk.TextEnd("text-1"), model)
        messages = messages.handleStreamChunk(StreamChunk.Finish(finishReason = "stop"), model)

        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals("hello", messages.last().toText())
        assertEquals(model.id, messages.last().modelId)
        assertNotNull(messages.last().finishedAt)
    }

    @Test
    fun `reasoning tool and usage events should preserve semantic order`() {
        var messages = listOf(UIMessage.user("use a tool"))

        messages = messages.handleStreamChunk(StreamChunk.ReasoningStart("reasoning-1"), model)
        messages = messages.handleStreamChunk(StreamChunk.ReasoningDelta("reasoning-1", "think"), model)
        messages = messages.handleStreamChunk(StreamChunk.ReasoningEnd("reasoning-1"), model)
        messages = messages.handleStreamChunk(StreamChunk.ToolCallStart("call-1"), model)
        messages = messages.handleStreamChunk(
            StreamChunk.ToolCallDelta(
                id = "call-1",
                toolNameDelta = "search",
                inputDelta = "{\"q\":\"test\"}",
            ),
            model,
        )
        messages = messages.handleStreamChunk(StreamChunk.ToolCallEnd("call-1"), model)
        messages = messages.handleStreamChunk(
            StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 5)),
            model,
        )

        val assistant = messages.last()
        val reasoning = assistant.parts[0] as UIMessagePart.Reasoning
        val tool = assistant.parts[1] as UIMessagePart.Tool
        assertEquals("think", reasoning.reasoning)
        assertNotNull(reasoning.finishedAt)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":\"test\"}", tool.input)
        assertEquals(15, assistant.usage?.totalTokens)
    }
}
