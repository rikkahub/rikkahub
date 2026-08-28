package me.rerere.ai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageVisibilityTest {
    @Test
    fun `client tool is visible UI content`() {
        val parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "ask_user",
                input = "{}",
            )
        )

        assertFalse(parts.isEmptyUIMessage())
    }

    @Test
    fun `server tool is visible UI content`() {
        val parts = listOf(
            UIMessagePart.ServerTool(
                toolCallId = "call_1",
                toolName = "web_search",
                status = ServerToolStatus.IN_PROGRESS,
            )
        )

        assertFalse(parts.isEmptyUIMessage())
    }

    @Test
    fun `blank text remains empty UI content`() {
        assertTrue(listOf(UIMessagePart.Text(" ")).isEmptyUIMessage())
    }
}
