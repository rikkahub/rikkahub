package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityProfileTest {
    @Test
    fun `legacy model json derives a compatible capability profile`() {
        val model = Json.decodeFromString<Model>(
            """{"modelId":"legacy","abilities":["TOOL","REASONING"]}"""
        )

        assertNull(model.capabilityProfile)
        assertTrue(model.effectiveCapabilityProfile().toolCalling)
        assertTrue(model.effectiveCapabilityProfile().reasoning)
        assertEquals(ToolCallIdStability.STABLE, model.effectiveCapabilityProfile().toolCallIdStability)
    }

    @Test
    fun `invalid custom profile is rejected`() {
        val invalid = ModelCapabilityProfile(parallelToolCalls = true)

        assertEquals("parallelToolCalls requires toolCalling", invalid.validationError())
    }
}
