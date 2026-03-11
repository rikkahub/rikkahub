package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeProviderStreamAccumulatorTest {

    @Test
    fun `input json delta should preserve escaped chars and unicode`() {
        val fullInput = """{"path":"EN × LUNÉLYS.md","content":"\n\n---\n\n**2026-03-07 新加入的：*測試* \"Quote\""}"""
        val quoteIndex = fullInput.indexOf("\"\\n\\n---")
        val partials = listOf(
            fullInput.substring(0, quoteIndex),
            fullInput.substring(quoteIndex, quoteIndex + 5),
            fullInput.substring(quoteIndex + 5)
        )

        val messages = processEvents(
            initialMessages = listOf(UIMessage.user("Write the note")),
            events = listOf(
                "content_block_start" to contentBlockStart(
                    index = 0,
                    contentBlock = toolUseBlock(id = "call_1", name = "write-note")
                ),
                "content_block_delta" to contentBlockDelta(0, inputJsonDelta(partials[0])),
                "content_block_delta" to contentBlockDelta(0, inputJsonDelta(partials[1])),
                "content_block_delta" to contentBlockDelta(0, inputJsonDelta(partials[2])),
                "content_block_stop" to contentBlockStop(0),
            )
        )

        val tool = messages.last().getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("write-note", tool.toolName)
        assertEquals(fullInput, tool.input)

        val arguments = json.parseToJsonElement(tool.input).jsonObject
        assertEquals("EN × LUNÉLYS.md", arguments["path"]?.jsonPrimitive?.content)
        assertEquals("\n\n---\n\n**2026-03-07 新加入的：*測試* \"Quote\"", arguments["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `new tool call should stay isolated from previously executed tool in same assistant message`() {
        val previousTool = UIMessagePart.Tool(
            toolCallId = "call_prev",
            toolName = "append-to-section",
            input = """{"path":"old.md","content":"old"}""",
            output = listOf(UIMessagePart.Text("done"))
        )
        val newInput = """{"path":"fresh.md","content":"new"}"""
        val messages = processEvents(
            initialMessages = listOf(
                UIMessage.user("Continue editing"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("Previous step"),
                        previousTool
                    )
                )
            ),
            events = listOf(
                "content_block_start" to contentBlockStart(
                    index = 0,
                    contentBlock = toolUseBlock(id = "call_new", name = "replace-section")
                ),
                "content_block_delta" to contentBlockDelta(0, inputJsonDelta(newInput.substring(0, 18))),
                "content_block_delta" to contentBlockDelta(0, inputJsonDelta(newInput.substring(18))),
                "content_block_stop" to contentBlockStop(0),
            )
        )

        val tools = messages.last().getTools()
        assertEquals(2, tools.size)
        assertEquals("""{"path":"old.md","content":"old"}""", tools[0].input)
        assertTrue(tools[0].isExecuted)
        assertEquals("call_new", tools[1].toolCallId)
        assertEquals(newInput, tools[1].input)
    }

    @Test
    fun `mixed content blocks should preserve order and bind tool args by index`() {
        val toolInput = """{"path":"notes.md","content":"hello"}"""
        val messages = processEvents(
            initialMessages = listOf(UIMessage.user("Plan and write")),
            events = listOf(
                "content_block_start" to contentBlockStart(
                    index = 0,
                    contentBlock = textBlock("")
                ),
                "content_block_delta" to contentBlockDelta(0, textDelta("Plan: ")),
                "content_block_stop" to contentBlockStop(0),
                "content_block_start" to contentBlockStart(
                    index = 1,
                    contentBlock = thinkingBlock("")
                ),
                "content_block_delta" to contentBlockDelta(1, thinkingDelta("需要先写入文件")),
                "content_block_delta" to contentBlockDelta(1, signatureDelta("sig_1")),
                "content_block_stop" to contentBlockStop(1),
                "content_block_start" to contentBlockStart(
                    index = 2,
                    contentBlock = toolUseBlock(id = "call_mix", name = "write-note")
                ),
                "content_block_delta" to contentBlockDelta(2, inputJsonDelta(toolInput.substring(0, 20))),
                "content_block_delta" to contentBlockDelta(2, inputJsonDelta(toolInput.substring(20))),
                "content_block_stop" to contentBlockStop(2),
            )
        )

        val parts = messages.last().parts
        assertEquals(3, parts.size)
        assertTrue(parts[0] is UIMessagePart.Text)
        assertTrue(parts[1] is UIMessagePart.Reasoning)
        assertTrue(parts[2] is UIMessagePart.Tool)
        assertEquals("Plan: ", (parts[0] as UIMessagePart.Text).text)
        assertEquals("需要先写入文件", (parts[1] as UIMessagePart.Reasoning).reasoning)
        assertEquals("sig_1", (parts[1] as UIMessagePart.Reasoning).metadata?.get("signature")?.jsonPrimitive?.content)
        assertEquals("call_mix", (parts[2] as UIMessagePart.Tool).toolCallId)
        assertEquals(toolInput, (parts[2] as UIMessagePart.Tool).input)
    }

    private fun processEvents(
        initialMessages: List<UIMessage>,
        events: List<Pair<String, JsonObject>>
    ): List<UIMessage> {
        val accumulator = ClaudeStreamEventAccumulator()
        var messages = initialMessages

        events.forEach { (eventType, data) ->
            val chunk = MessageChunk(
                id = "msg_1",
                model = "claude-test",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = accumulator.parseEvent(eventType, data),
                        message = null,
                        finishReason = null
                    )
                )
            )
            messages = messages.handleMessageChunk(chunk)
        }

        return messages
    }

    private fun contentBlockStart(index: Int, contentBlock: JsonObject) = buildJsonObject {
        put("index", index)
        put("content_block", contentBlock)
    }

    private fun contentBlockDelta(index: Int, delta: JsonObject) = buildJsonObject {
        put("index", index)
        put("delta", delta)
    }

    private fun contentBlockStop(index: Int) = buildJsonObject {
        put("index", index)
    }

    private fun toolUseBlock(id: String, name: String) = buildJsonObject {
        put("type", "tool_use")
        put("id", id)
        put("name", name)
        put("input", buildJsonObject {})
    }

    private fun textBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun thinkingBlock(thinking: String) = buildJsonObject {
        put("type", "thinking")
        put("thinking", thinking)
    }

    private fun textDelta(text: String) = buildJsonObject {
        put("type", "text_delta")
        put("text", text)
    }

    private fun thinkingDelta(thinking: String) = buildJsonObject {
        put("type", "thinking_delta")
        put("thinking", thinking)
    }

    private fun signatureDelta(signature: String) = buildJsonObject {
        put("type", "signature_delta")
        put("signature", signature)
    }

    private fun inputJsonDelta(partialJson: String) = buildJsonObject {
        put("type", "input_json_delta")
        put("partial_json", partialJson)
    }
}
