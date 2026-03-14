package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageToolsTest {
    @Test
    fun `running tool should still open preview sheet`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "search_web",
            input = """{"query":"slow request"}""",
            output = emptyList(),
            approvalState = ToolApprovalState.Auto,
        )

        assertTrue(tool.canOpenPreviewSheet())
    }

    @Test
    fun `ask user tool should use inline interaction instead of preview sheet`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_2",
            toolName = "ask_user",
            input = """{"questions":[]}""",
        )

        assertFalse(tool.canOpenPreviewSheet())
    }

    @Test
    fun `pending termux exec should inline command preview`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_3",
            toolName = "termux_exec",
            input = """{"command":"ls -la /sdcard"}""",
            approvalState = ToolApprovalState.Pending,
        )

        val preview = requireNotNull(tool.pendingApprovalPreview())

        assertEquals("bash", preview.language)
        assertEquals("ls -la /sdcard", preview.code)
    }

    @Test
    fun `pending generic tool should inline json preview`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_4",
            toolName = "custom_tool",
            input = """{"url":"https://example.com","depth":2}""",
            approvalState = ToolApprovalState.Pending,
        )

        val preview = requireNotNull(tool.pendingApprovalPreview())

        assertEquals("json", preview.language)
        assertTrue(preview.code.contains("\"url\""))
        assertTrue(preview.code.contains("https://example.com"))
    }
}
