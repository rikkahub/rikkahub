package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class SensorToolTest {

    // list_sensors and the read success path require SensorManager — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `read_sensor throws when type is missing`() {
        // Tool calls error("type is required") -> IllegalStateException
        val tool = readSensorTool(NULL_CONTEXT)
        execTool(tool, """{}""")
    }

    @Test
    fun `read_sensor returns error envelope for unknown sensor type`() {
        // Unknown-type validation runs before getSystemService, so a null Context is fine.
        val tool = readSensorTool(NULL_CONTEXT)
        val result = execTool(tool, """{"type":"nonsense"}""")
        assertTrue(
            "expected unknown-sensor-type error, got: $result",
            result.contains("\"error\"") && result.contains("unknown sensor type")
        )
    }
}
