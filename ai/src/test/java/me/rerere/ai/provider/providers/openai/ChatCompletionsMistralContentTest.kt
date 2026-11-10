package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mistral 开启推理时 content 是块列表
 * https://docs.mistral.ai/capabilities/reasoning
 */
class ChatCompletionsMistralContentTest {
    @Test
    fun `stream should keep first text chunk in thinking-to-answer transition frame`() {
        val decoder = ChatCompletionsStreamDecoder()
        val lines = listOf(
            // 思考中：content 是只有 thinking 块的列表
            """{"id":"r1","choices":[{"index":0,"delta":{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"17 * 23"}]}]},"finish_reason":null}]}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":" = 391"}]}]},"finish_reason":null}]}""",
            // 过渡帧：收尾的 thinking 块 + 回答的第一个 text 块
            """{"id":"r1","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"."}],"closed":true},{"type":"text","text":"The answer"}]},"finish_reason":null}]}""",
            // 回答中：content 是普通字符串
            """{"id":"r1","choices":[{"index":0,"delta":{"content":" is 391."},"finish_reason":null}]}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}""",
        )
        val chunks = lines.flatMap { decoder.accept(SseEvent(data = it)).chunks }

        assertEquals(
            "17 * 23 = 391.",
            chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals(
            "The answer is 391.",
            chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `non streaming response should parse both thinking and text chunks`() {
        val message = json.parseToJsonElement(
            """{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"think "},{"type":"text","text":"more"}]},{"type":"text","text":"Answer"}]}"""
        ).jsonObject

        val parsed = invokeParseMessage(message)

        assertEquals("think more", parsed.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("Answer", parsed.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `string content should still parse as text`() {
        val parsed = parseChatCompletionsContent(json.parseToJsonElement("\"Hi\""))
        assertEquals(ChatCompletionsContent("Hi", null), parsed)
    }

    private fun invokeParseMessage(message: JsonObject): UIMessage {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(api, message) as UIMessage
    }
}
