package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseApiStreamingGenerationTest {
    @Test
    fun `streaming generation aggregates chunks into non streaming result`() = runBlocking {
        val usage = TokenUsage(promptTokens = 3, completionTokens = 2, totalTokens = 5)

        val result = collectStreamingTextGeneration(
            model = Model(modelId = "gpt-5.6-sol", displayName = "GPT-5.6 Sol"),
            stream = flowOf(
                StreamChunk.TextStart("text-1"),
                StreamChunk.TextDelta("text-1", "Hello"),
                StreamChunk.TextDelta("text-1", " world"),
                StreamChunk.TextEnd("text-1"),
                StreamChunk.Usage(usage),
                StreamChunk.Finish(
                    finishReason = "completed",
                    responseId = "resp_123",
                    model = "gpt-5.6-sol",
                ),
            ),
        )

        assertEquals("resp_123", result.id)
        assertEquals("gpt-5.6-sol", result.model)
        assertEquals("completed", result.finishReason)
        assertEquals(usage, result.usage)
        assertEquals("Hello world", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }
}
