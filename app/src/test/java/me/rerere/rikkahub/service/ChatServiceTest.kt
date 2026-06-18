package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `synthetic tool name is provider-safe`() {
        val regex = Regex("^[a-zA-Z0-9_-]{1,64}$")
        val unsafeKind = "workspace_shell.completed"

        val sanitizedName = sanitizeSyntheticToolName(unsafeKind)

        assertEquals("workspace_shell_completed", sanitizedName)
        assertTrue("synthetic tool name must match Anthropic tool-name allowlist", regex.matches(sanitizedName))
        assertEquals(
            "long names must be truncated to provider limit",
            64,
            sanitizeSyntheticToolName("x".repeat(120)).length,
        )
        // Total over the edge cases: an empty or all-invalid kind sanitizes to "" which would violate
        // the min-length-1 contract, so it must fall back to a valid non-empty name.
        assertTrue("empty kind must yield a provider-safe name", regex.matches(sanitizeSyntheticToolName("")))
        assertTrue("all-invalid kind must yield a provider-safe name", regex.matches(sanitizeSyntheticToolName("...")))
    }
}
