package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class VibrateToolTest {

    // Success path & empty-pattern path both require a real Vibrator system service —
    // empty-pattern validation runs AFTER context.getSystemService(...) in production,
    // so it cannot be exercised in a JVM unit test. Instrumented test required.

    @Test
    fun `vibrate rejects mutually-exclusive duration_ms and pattern`() {
        val tool = vibrateTool(NULL_CONTEXT)
        val result = execTool(tool, """{"duration_ms":100,"pattern":[0,200]}""")
        assertTrue(
            "expected error envelope, got: $result",
            result.contains("\"error\"") && result.contains("not both")
        )
    }
}
