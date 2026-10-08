package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidatorTest {
    private val user = UIMessage.user("I moved to Hangzhou last month")

    private fun assistant(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(*parts))

    private fun tool(name: String, executed: Boolean = true) = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = "{}",
        output = if (executed) listOf(UIMessagePart.Text("{}")) else emptyList(),
    )

    @Test
    fun `a finished exchange is consolidated`() {
        assertTrue(shouldConsolidate(listOf(user, assistant(UIMessagePart.Text("Nice!")))))
        assertTrue(
            shouldConsolidate(
                listOf(user, assistant(tool(MemoryToolNames.READ), tool("search_web"), UIMessagePart.Text("Nice!")))
            )
        )
    }

    @Test
    fun `an exchange where the model already wrote memory is skipped`() {
        val exchange = listOf(user, assistant(tool(MemoryToolNames.STR_REPLACE), UIMessagePart.Text("Done")))

        assertFalse(shouldConsolidate(exchange))
    }

    @Test
    fun `an unfinished exchange is skipped`() {
        assertFalse(shouldConsolidate(listOf(user)))
        assertFalse(shouldConsolidate(listOf(user, assistant(tool("workspace_shell", executed = false)))))
    }

    @Test
    fun `an exchange without user text is skipped`() {
        val imageOnly = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(url = "file:///a.png")))

        assertFalse(shouldConsolidate(listOf(imageOnly, assistant(UIMessagePart.Text("A cat")))))
    }
}
