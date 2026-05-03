package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScannerToolTest {

    // Success path requires MediaScannerConnection — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `scan_media throws when paths is missing`() {
        // Tool calls error("paths is required") -> IllegalStateException
        val tool = mediaScannerTool(NULL_CONTEXT)
        execTool(tool, """{}""")
    }

    @Test
    fun `scan_media returns error envelope when paths is empty`() {
        val tool = mediaScannerTool(NULL_CONTEXT)
        val result = execTool(tool, """{"paths":[]}""")
        assertTrue(
            "expected error envelope, got: $result",
            result.contains("\"error\"") && result.contains("paths must not be empty")
        )
    }

    @Test
    fun `scan_media returns error envelope when paths contains only empty strings`() {
        val tool = mediaScannerTool(NULL_CONTEXT)
        val result = execTool(tool, """{"paths":["",""]}""")
        assertTrue(
            "expected error envelope, got: $result",
            result.contains("\"error\"") && result.contains("paths must not be empty")
        )
    }
}
