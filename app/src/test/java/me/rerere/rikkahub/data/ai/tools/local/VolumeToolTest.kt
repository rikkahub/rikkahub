package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeToolTest {

    // get_volume / set_volume success paths require AudioManager — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `set_volume throws when stream is missing`() {
        // Tool calls error("stream is required") -> IllegalStateException
        val tool = setVolumeTool(NULL_CONTEXT)
        execTool(tool, """{"percent":50}""")
    }

    @Test(expected = IllegalStateException::class)
    fun `set_volume throws when percent is missing`() {
        // Tool calls error("percent is required") -> IllegalStateException
        val tool = setVolumeTool(NULL_CONTEXT)
        execTool(tool, """{"stream":"media"}""")
    }

    @Test
    fun `set_volume returns error envelope for unknown stream`() {
        // Unknown-stream validation runs before any Context call.
        val tool = setVolumeTool(NULL_CONTEXT)
        val result = execTool(tool, """{"stream":"foo","percent":50}""")
        assertTrue(
            "expected unknown-stream error, got: $result",
            result.contains("\"error\"") && result.contains("unknown stream")
        )
    }
}
