package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class LocationToolTest {

    // Successful location fix requires FusedLocationProviderClient — instrumented test required.

    @Test
    fun `get_location returns error envelope for unknown accuracy`() {
        // Unknown-accuracy validation runs before any Context call.
        val tool = locationTool(NULL_CONTEXT)
        val result = execTool(tool, """{"accuracy":"foo"}""")
        assertTrue(
            "expected unknown-accuracy error, got: $result",
            result.contains("\"error\"") && result.contains("unknown accuracy")
        )
    }
}
