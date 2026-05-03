package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalActionToolTest {

    @Test
    fun `global_action rejects missing action`() {
        val tool = globalActionTool()
        val result = execTool(tool, """{}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `global_action rejects unknown action`() {
        val tool = globalActionTool()
        val result = execTool(tool, """{"action":"reboot"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `global_action returns service-not-active when offline`() {
        val tool = globalActionTool()
        val result = execTool(tool, """{"action":"home"}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }
}
