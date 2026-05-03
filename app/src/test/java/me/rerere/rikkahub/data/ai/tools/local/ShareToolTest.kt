package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class ShareToolTest {

    // Success path requires Context.startActivity — instrumented test required.

    @Test
    fun `share returns error when both text and url are missing`() {
        val tool = shareTool(NULL_CONTEXT)
        val result = execTool(tool, """{}""")
        assertTrue(
            "expected error envelope, got: $result",
            result.contains("\"error\"") && result.contains("at least one of text or url")
        )
    }

    @Test
    fun `share returns error when text and url are both empty strings`() {
        val tool = shareTool(NULL_CONTEXT)
        val result = execTool(tool, """{"text":"","url":""}""")
        assertTrue(
            "expected error envelope, got: $result",
            result.contains("\"error\"") && result.contains("at least one of text or url")
        )
    }
}
