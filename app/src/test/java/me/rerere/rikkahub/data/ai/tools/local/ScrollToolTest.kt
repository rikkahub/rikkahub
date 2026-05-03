package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollToolTest {

    @Test
    fun `scroll rejects missing direction`() {
        val tool = scrollTool()
        val result = execTool(tool, """{}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `scroll rejects unknown direction`() {
        val tool = scrollTool()
        val result = execTool(tool, """{"direction":"diagonal"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `scroll returns service-not-active when offline`() {
        val tool = scrollTool()
        val result = execTool(tool, """{"direction":"down"}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }
}
