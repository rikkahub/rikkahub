package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsStreamDecoderNullTest {
    @Test
    fun `explicit null fields from compatible relays do not break decoding`() {
        val decoder = ChatCompletionsStreamDecoder()
        val lines = listOf(
            """{"id":"r1","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"We"},"finish_reason":null}],"usage":null}""",
            "null",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":null,"reasoning_content":" need","reasoning_details":[null]},"finish_reason":null}],"usage":null}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":"Hi","annotations":[null,{"type":"url_citation","url_citation":null}]},"finish_reason":null}],"usage":null}""",
        )
        val chunks = lines.flatMap { decoder.accept(SseEvent(data = it)).chunks }

        assertEquals(
            "We need",
            chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals("Hi", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals(1, chunks.filterIsInstance<StreamChunk.Annotations>().single().annotations.size)
    }
}
