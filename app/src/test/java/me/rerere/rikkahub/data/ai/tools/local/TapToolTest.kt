package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class TapToolTest {

    @Test
    fun `tap rejects missing x`() {
        val tool = tapTool()
        val result = execTool(tool, """{"y":100}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `tap rejects negative y`() {
        val tool = tapTool()
        val result = execTool(tool, """{"x":100,"y":-5}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `tap returns service-not-active envelope when service is offline`() {
        val tool = tapTool()
        val result = execTool(tool, """{"x":100,"y":200}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    @Test
    fun `long_press rejects out-of-range duration_ms`() {
        val tool = longPressTool()
        val result = execTool(tool, """{"x":1,"y":1,"duration_ms":50}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `long_press rejects duration_ms above ceiling`() {
        val tool = longPressTool()
        val result = execTool(tool, """{"x":1,"y":1,"duration_ms":10000}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }
}
