package me.rerere.ai.provider.providers

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CLAUDE_CONTEXT_1M_BETA = "context-1m-2025-08-07"

class ClaudeProviderOAuthBetaHeaderTest {
    @Test
    fun `context-1m beta is present iff toggle and model support it`() {
        runBlocking {
            checkAll(200, Arb.string(0..32), Arb.boolean()) { modelId, oauthContext1M ->
                val header = buildClaudeOAuthBetaHeader(oauthContext1M, Model(modelId))
                val expected = oauthContext1M && ModelRegistry.supportsClaude1MContext(modelId)
                assertEquals(
                    "toggle=$oauthContext1M, modelId=$modelId",
                    expected,
                    header.contains(CLAUDE_CONTEXT_1M_BETA)
                )
            }
        }
    }

    @Test
    fun `toggle on with haiku excludes context-1m beta`() {
        assertFalse(
            buildClaudeOAuthBetaHeader(
                true,
                Model(modelId = "claude-haiku-3-5-20251022")
            ).contains(CLAUDE_CONTEXT_1M_BETA)
        )
    }

    @Test
    fun `toggle on with sonnet opus and fable includes context-1m beta`() {
        assertTrue(
            buildClaudeOAuthBetaHeader(
                true,
                Model(modelId = "claude-sonnet-4-6")
            ).contains(CLAUDE_CONTEXT_1M_BETA)
        )
        assertTrue(
            buildClaudeOAuthBetaHeader(
                true,
                Model(modelId = "claude-opus-4-8")
            ).contains(CLAUDE_CONTEXT_1M_BETA)
        )
        assertTrue(
            buildClaudeOAuthBetaHeader(
                true,
                Model(modelId = "claude-fable-3-1")
            ).contains(CLAUDE_CONTEXT_1M_BETA)
        )
    }

    @Test
    fun `toggle off keeps context-1m beta absent for capable models`() {
        assertFalse(
            buildClaudeOAuthBetaHeader(
                false,
                Model(modelId = "claude-sonnet-4-6")
            ).contains(CLAUDE_CONTEXT_1M_BETA)
        )
        assertFalse(
            buildClaudeOAuthBetaHeader(
                false,
                Model(modelId = "claude-opus-4-8")
            ).contains(CLAUDE_CONTEXT_1M_BETA)
        )
        assertFalse(
            buildClaudeOAuthBetaHeader(
                false,
                Model(modelId = "claude-fable-3-1")
            ).contains(CLAUDE_CONTEXT_1M_BETA)
        )
    }

    @Test
    fun `model null disables context-1m beta`() {
        assertFalse(buildClaudeOAuthBetaHeader(true, null).contains(CLAUDE_CONTEXT_1M_BETA))
    }

    @Test
    fun `unknown model ids do not receive context-1m beta`() {
        val unknownModelIds = listOf("gemini-2.5-flash", "deepseek-r1", "llama-3.1", "")
        unknownModelIds.forEach { modelId ->
            assertFalse(
                buildClaudeOAuthBetaHeader(
                    true,
                    Model(modelId)
                ).contains(CLAUDE_CONTEXT_1M_BETA)
            )
        }
    }

    @Test
    fun `haiku to sonnet switch flips context-1m beta presence`() {
        val capabilityModel = Model(modelId = "claude-haiku-3-5-20251022")
        val capableModel = Model(modelId = "claude-sonnet-4-6")
        assertFalse(
            buildClaudeOAuthBetaHeader(true, capabilityModel).contains(CLAUDE_CONTEXT_1M_BETA)
        )
        assertTrue(
            buildClaudeOAuthBetaHeader(true, capableModel).contains(CLAUDE_CONTEXT_1M_BETA)
        )
    }

    @Test
    fun `toggle flip flips context-1m beta presence`() {
        val model = Model(modelId = "claude-opus-4-8")
        assertFalse(buildClaudeOAuthBetaHeader(false, model).contains(CLAUDE_CONTEXT_1M_BETA))
        assertTrue(buildClaudeOAuthBetaHeader(true, model).contains(CLAUDE_CONTEXT_1M_BETA))
    }
}
