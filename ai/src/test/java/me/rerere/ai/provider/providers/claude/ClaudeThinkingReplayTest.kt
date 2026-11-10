package me.rerere.ai.provider.providers.claude

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 思考块要原样回传：解析、合并、落盘再构造请求之后，每一块的文本、签名和顺序都不能变。
 */
class ClaudeThinkingReplayTest {
    private val provider = ClaudeProvider(OkHttpClient())
    private val model = Model(modelId = "claude-test")

    private fun blocks(value: String): JsonArray = json.parseToJsonElement(value).jsonArray

    private fun assistantBlocks(messages: List<UIMessage>): List<JsonObject> {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            ClaudePromptCacheTtl::class.java,
        )
        method.isAccessible = true
        val request = method.invoke(provider, messages, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
        return request
            .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .flatMap { it.jsonObject.getValue("content").jsonArray.map { block -> block.jsonObject } }
    }

    private fun stream(vararg events: Pair<String, String>): UIMessage {
        val decoder = ClaudeStreamDecoder()
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("first"))
        events.forEach { (name, data) ->
            decoder.accept(SseEvent(event = name, data = data)).chunks.forEach {
                messages = handler.handle(messages, it)
            }
        }
        // 模拟落盘后再读出来
        return json.decodeFromString<UIMessage>(json.encodeToString(UIMessage.serializer(), messages.last()))
    }

    @Test
    fun `non streaming adjacent thinking blocks should keep their own signatures`() = runTest {
        val content = blocks(
            """[
            {"type":"thinking","thinking":"Thought A","signature":"sig-A"},
            {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
            {"type":"text","text":"answer"}
        ]"""
        )
        val history = listOf(UIMessage.user("first"))

        val result = generateClaudeWithPauseTurn(history, model) {
            TextGenerationResult("resp-1", model.modelId, provider.parseMessage(content), "end_turn")
        }

        assertEquals(content.toList(), assistantBlocks(history + result.message + UIMessage.user("second")))
    }

    @Test
    fun `pause turn continuation should not merge thinking blocks of different responses`() = runTest {
        val first = blocks("""[{"type":"thinking","thinking":"Thought A","signature":"sig-A"}]""")
        val second = blocks(
            """[
            {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
            {"type":"text","text":"answer"}
        ]"""
        )
        val history = listOf(UIMessage.user("first"))
        var calls = 0

        val result = generateClaudeWithPauseTurn(history, model) { messages ->
            if (calls++ == 0) {
                TextGenerationResult("resp-1", model.modelId, provider.parseMessage(first), "pause_turn")
            } else {
                assertEquals(first.toList(), assistantBlocks(messages))
                TextGenerationResult("resp-2", model.modelId, provider.parseMessage(second), "end_turn")
            }
        }

        assertEquals(
            first.toList() + second.toList(),
            assistantBlocks(history + result.message + UIMessage.user("second")),
        )
    }

    @Test
    fun `non streaming redacted thinking should be replayed`() {
        val content = blocks(
            """[
            {"type":"redacted_thinking","data":"opaque-data"},
            {"type":"text","text":"answer"}
        ]"""
        )

        val message = provider.parseMessage(content)

        assertEquals(
            content.toList(),
            assistantBlocks(listOf(UIMessage.user("first"), message, UIMessage.user("second"))),
        )
    }

    @Test
    fun `streamed signature deltas should accumulate within their block`() {
        val message = stream(
            "content_block_start" to """{"index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"thinking_delta","thinking":"Thought A"}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"signature_delta","signature":"sig-"}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"signature_delta","signature":"A"}}""",
            "content_block_stop" to """{"index":0}""",
            "content_block_start" to """{"index":1,"content_block":{"type":"thinking","thinking":""}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"thinking_delta","thinking":"Thought B"}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"signature_delta","signature":"sig-B"}}""",
            "content_block_stop" to """{"index":1}""",
            "content_block_start" to """{"index":2,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"index":2,"delta":{"type":"text_delta","text":"answer"}}""",
            "content_block_stop" to """{"index":2}""",
            "message_stop" to "{}",
        )

        assertEquals(
            blocks(
                """[
                {"type":"thinking","thinking":"Thought A","signature":"sig-A"},
                {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
                {"type":"text","text":"answer"}
            ]"""
            ).toList(),
            assistantBlocks(listOf(UIMessage.user("first"), message, UIMessage.user("second"))),
        )
    }

    @Test
    fun `streamed redacted thinking should be replayed`() {
        val message = stream(
            "content_block_start" to """{"index":0,"content_block":{"type":"redacted_thinking","data":"opaque-data"}}""",
            "content_block_stop" to """{"index":0}""",
            "content_block_start" to """{"index":1,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"text_delta","text":"answer"}}""",
            "content_block_stop" to """{"index":1}""",
            "message_stop" to "{}",
        )

        assertEquals(
            blocks(
                """[
                {"type":"redacted_thinking","data":"opaque-data"},
                {"type":"text","text":"answer"}
            ]"""
            ).toList(),
            assistantBlocks(listOf(UIMessage.user("first"), message, UIMessage.user("second"))),
        )
    }
}
