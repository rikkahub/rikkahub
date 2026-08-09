package me.rerere.ai.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StreamChunkHandlerTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `text lifecycle should create and update assistant message`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "hel"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "lo"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals("hello", messages.last().toText())
        assertEquals(model.id, messages.last().modelId)
        assertNotNull(messages.last().finishedAt)
    }

    @Test
    fun `reasoning tool and usage events should preserve semantic order`() {
        var messages = listOf(UIMessage.user("use a tool"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ReasoningStart("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ReasoningDelta("reasoning-1", "think"))
        messages = handler.handle(messages, StreamChunk.ReasoningEnd("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-1"))
        messages = handler.handle(messages,
            StreamChunk.ToolCallDelta(
                id = "call-1",
                toolNameDelta = "search",
                inputDelta = "{\"q\":\"test\"}",
            ),
        )
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call-1"))
        messages = handler.handle(messages,
            StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 5)),
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

    @Test
    fun `interleaved text chunks should be merged by id`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "A"))
        messages = handler.handle(messages, StreamChunk.TextStart("text-2"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-2", "B"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "C"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-2"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))

        val textParts = messages.last().parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("AC", "B"), textParts.map { it.text })
    }

    @Test
    fun `image snapshots should replace previous image data`() {
        var messages = listOf(UIMessage.user("draw an image"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ImageStart("image-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-2"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "final"))
        messages = handler.handle(messages, StreamChunk.ImageEnd("image-1"))

        val image = messages.last().parts.single() as UIMessagePart.Image
        assertEquals("data:image/png;base64,final", image.url)
    }

    @Test
    fun `non streaming result should keep image data url intact`() {
        val messages = listOf(UIMessage.user("draw an image"))
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/jpeg;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/jpeg;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `non streaming result appended to assistant turn should not merge images`() {
        val messages = listOf(
            UIMessage.user("draw an image"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("sure"))),
        )
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/png;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/png;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `server tool lifecycle should merge streamed input result and metadata`() {
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ServerToolStart(
            id = "srv-1",
            toolName = "web_search",
            metadata = buildJsonObject { put("call", "raw") },
        ))
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "{\"query\":"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "\"Kotlin\"}"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputEnd("srv-1"))
        messages = handler.handle(messages, StreamChunk.ServerToolEnd(
            id = "srv-1",
            output = buildJsonObject { put("result", "docs") },
            status = ServerToolStatus.COMPLETED,
            metadata = buildJsonObject { put("result", "raw") },
        ))

        val tool = messages.last().parts.single() as UIMessagePart.ServerTool
        assertEquals("web_search", tool.toolName)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals("docs", tool.output?.jsonObject?.get("result")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals("raw", tool.metadata?.get("call")?.jsonPrimitive?.content)
        assertEquals("raw", tool.metadata?.get("result")?.jsonPrimitive?.content)
    }
}
