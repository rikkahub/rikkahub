package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTreeToolTest {

    @Test
    fun `read_window_tree returns service-not-active when offline`() {
        val tool = readWindowTreeTool()
        val result = execTool(tool, """{}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    @Test
    fun `read_window_tree accepts verbose param without error`() {
        val tool = readWindowTreeTool()
        val result = execTool(tool, """{"verbose":true}""")
        // Service is offline in JVM tests, so we still get the not-active envelope —
        // but no parameter-validation error.
        assertTrue(result.contains("AccessibilityService not active"))
    }

    @Test
    fun `read_window_tree accepts max_nodes param`() {
        val tool = readWindowTreeTool()
        val result = execTool(tool, """{"max_nodes":50}""")
        assertTrue(result.contains("AccessibilityService not active"))
    }
}
