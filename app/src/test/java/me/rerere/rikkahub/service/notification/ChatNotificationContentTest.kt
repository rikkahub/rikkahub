package me.rerere.rikkahub.service.notification

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic test for [determineNotificationContent], the Live Update notification content selector
 * extracted out of ChatService. Sentinel [NotificationStrings] let each branch assert the exact
 * (chip, status, content) triple, so any drift in branch order or truncation rules fails here. No
 * Android/Robolectric — UIMessagePart lives in the pure :ai module.
 */
class ChatNotificationContentTest {

    private val strings = NotificationStrings(
        chipTool = "chipTool",
        chipThinking = "chipThinking",
        chipWriting = "chipWriting",
        statusThinking = "thinking",
        statusWriting = "writing",
        statusDefault = "default",
        tool = { name -> "tool:$name" },
    )

    @Test
    fun `pending tool yields tool chip with mcp prefix stripped and input truncated to 100`() {
        val input = "x".repeat(150)
        val parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "id",
                toolName = "mcp__search",
                input = input,
                output = emptyList(), // isExecuted == false
            )
        )

        val result = determineNotificationContent(parts, strings)

        assertEquals("chipTool", result.chipText)
        assertEquals("tool:search", result.statusText)
        assertEquals(input.take(100), result.contentText)
    }

    @Test
    fun `unfinished reasoning yields thinking chip with reasoning tail truncated to 200`() {
        val reasoning = "r".repeat(300)
        val parts = listOf(
            UIMessagePart.Reasoning(reasoning = reasoning, finishedAt = null)
        )

        val result = determineNotificationContent(parts, strings)

        assertEquals("chipThinking", result.chipText)
        assertEquals("thinking", result.statusText)
        assertEquals(reasoning.takeLast(200), result.contentText)
    }

    @Test
    fun `text yields writing chip with text tail truncated to 200`() {
        val text = "t".repeat(300)
        val parts = listOf(UIMessagePart.Text(text = text))

        val result = determineNotificationContent(parts, strings)

        assertEquals("chipWriting", result.chipText)
        assertEquals("writing", result.statusText)
        assertEquals(text.takeLast(200), result.contentText)
    }

    @Test
    fun `empty parts yields default writing chip with empty content`() {
        val result = determineNotificationContent(emptyList(), strings)

        assertEquals("chipWriting", result.chipText)
        assertEquals("default", result.statusText)
        assertEquals("", result.contentText)
    }
}
