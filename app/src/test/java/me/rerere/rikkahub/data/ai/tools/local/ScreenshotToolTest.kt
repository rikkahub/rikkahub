package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotToolTest {

    @Test
    fun `take_screenshot returns service-not-active when offline`() {
        val tool = takeScreenshotTool(NULL_CONTEXT)
        val result = execTool(tool, """{}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    @Test
    fun `take_screenshot accepts display_id param`() {
        val tool = takeScreenshotTool(NULL_CONTEXT)
        val result = execTool(tool, """{"display_id":1}""")
        // Service is offline in JVM tests — still returns the not-active envelope, no validation error.
        assertTrue(result.contains("AccessibilityService not active"))
    }
}
