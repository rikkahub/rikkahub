package me.rerere.ai.runtime

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I-DELIMIT gating (#197 HP-2, design note security-model-design:197 §4.5). The untrusted-tool-content
 * clause is part of the system prompt exactly when the generation has a tool-content channel to fence —
 * tools exposed this request, or tool output already present in the included transcript (issue #356 #4).
 * A no-tool, no-tool-output chat must stay clause-free (no behavior change for plain chats). The string
 * content is pinned so the directive cannot be silently softened.
 */
class UntrustedToolContentClauseTest {

    @Test
    fun `clause is present only when tools are exposed`() {
        assertEquals("a no-tool generation must not carry the clause", "", untrustedToolContentClauseFor(false))
        assertEquals(
            "a tool-using generation must carry the clause",
            UNTRUSTED_TOOL_CONTENT_CLAUSE,
            untrustedToolContentClauseFor(true),
        )
    }

    @Test
    fun `clause states the data-not-instructions directive`() {
        val clause = untrustedToolContentClauseFor(true)
        assertTrue("must mark tool content as untrusted data", clause.contains("untrusted DATA", ignoreCase = true))
        assertTrue("must forbid following embedded instructions", clause.contains("not as instructions"))
        // The user-consent carve-out: the model may still act when the USER asked for it.
        assertTrue("must keep the user-initiated carve-out", clause.contains("unless the user"))
    }

    // Issue #356 #4: hasToolOutput is the historical-transcript gate. A clause must fire when prior tool
    // output is in scope even with NO tools exposed this request.
    // DEPRECATION: deliberately constructs a legacy UIMessagePart.ToolResult — this test PINS that the
    // gate still detects that deprecated part type in old transcripts, so the deprecated constructor use
    // is the point of the test, not a migration target.
    @Suppress("DEPRECATION")
    @Test
    fun `hasToolOutput detects executed tools and legacy tool results only`() {
        val executedTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c", toolName = "t", input = "{}",
                    output = listOf(UIMessagePart.Text("result")),
                ),
            ),
        )
        val unexecutedTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "c", toolName = "t", input = "{}", output = emptyList()),
            ),
        )
        val legacyResult = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                UIMessagePart.ToolResult(
                    toolCallId = "c", toolName = "t",
                    content = JsonPrimitive("out"), arguments = JsonPrimitive("{}"),
                ),
            ),
        )
        // A non-text tool output (image/document) is still tool output: hasToolOutput keys on
        // output.isNotEmpty(), not on the output's part type. Pins against a "only count text" mutation.
        val imageOutputTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c", toolName = "generate_image", input = "{}",
                    output = listOf(UIMessagePart.Image(url = "file:///out.png")),
                ),
            ),
        )
        val plain = UIMessage.user("just text")

        assertTrue("a modern executed tool with output counts", listOf(executedTool).hasToolOutput())
        assertTrue("a tool whose output is a non-text part still counts", listOf(imageOutputTool).hasToolOutput())
        assertTrue("a legacy ToolResult part counts", listOf(legacyResult).hasToolOutput())
        assertTrue("any message in the list carrying output counts", listOf(plain, executedTool).hasToolOutput())
        assertFalse("an unexecuted tool (no output) is not tool output", listOf(unexecutedTool).hasToolOutput())
        assertFalse("a plain chat has no tool output", listOf(plain).hasToolOutput())
        assertFalse("an empty transcript has no tool output", emptyList<UIMessage>().hasToolOutput())
    }
}
