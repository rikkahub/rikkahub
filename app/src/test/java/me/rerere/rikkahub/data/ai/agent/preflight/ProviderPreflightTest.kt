package me.rerere.rikkahub.data.ai.agent.preflight

import me.rerere.ai.provider.ModelCapabilityProfile
import me.rerere.ai.provider.ToolCallIdStability
import me.rerere.rikkahub.data.ai.agent.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPreflightTest {
    private fun request(
        profile: ModelCapabilityProfile,
        mode: AgentMode = AgentMode.CHAT,
        functionTools: Int = 0,
        nativeTools: Int = 0,
        requestedOutput: Int? = null,
        reserve: Int = 4_096,
    ) = ProviderPreflightRequest(
        mode = mode,
        capabilities = profile,
        resolvedFunctionToolCount = functionTools,
        configuredNativeToolCount = nativeTools,
        requestedOutputTokens = requestedOutput,
        outputReserveTokens = reserve,
        streamingRequested = true,
        reasoningRequested = true,
        multimodalInputRequested = false,
    )

    @Test
    fun `function tools unsupported by model blocks before send`() {
        val result = ProviderPreflight.evaluate(request(ModelCapabilityProfile(streaming = true), functionTools = 1))

        assertEquals(ProviderPreflightAction.BLOCK, result.action)
        assertTrue(ProviderPreflightCode.FUNCTION_TOOLS_UNSUPPORTED in result.codes)
    }

    @Test
    fun `agent mode blocks explicitly unstable tool call ids`() {
        val result = ProviderPreflight.evaluate(
            request(
                ModelCapabilityProfile(toolCalling = true, toolCallIdStability = ToolCallIdStability.UNSTABLE),
                mode = AgentMode.AGENT,
                functionTools = 1,
            )
        )

        assertEquals(ProviderPreflightAction.BLOCK, result.action)
        assertTrue(ProviderPreflightCode.UNSTABLE_TOOL_CALL_IDS in result.codes)
    }

    @Test
    fun `unknown tool call ids degrade to serial client identities instead of blocking agent`() {
        val result = ProviderPreflight.evaluate(
            request(
                ModelCapabilityProfile(
                    toolCalling = true,
                    parallelToolCalls = true,
                    toolCallIdStability = ToolCallIdStability.UNKNOWN,
                ),
                mode = AgentMode.AGENT,
                functionTools = 2,
            )
        )

        assertEquals(ProviderPreflightAction.DEGRADE, result.action)
        assertTrue(result.useClientGeneratedToolExecutionIdentity)
        assertFalse(result.allowParallelToolCalls)
        assertTrue(ProviderPreflightCode.CLIENT_GENERATED_TOOL_EXECUTION_IDS in result.codes)
        assertTrue(ProviderPreflightCode.PARALLEL_FUNCTION_TOOLS_DISABLED in result.codes)
    }

    @Test
    fun `google unknown ids use client identities and serial tools in chat too`() {
        val result = ProviderPreflight.evaluate(
            request(
                ModelCapabilityProfile(
                    toolCalling = true,
                    parallelToolCalls = true,
                    toolCallIdStability = ToolCallIdStability.UNKNOWN,
                ),
                functionTools = 2,
            ),
        )

        assertTrue(result.useClientGeneratedToolExecutionIdentity)
        assertFalse(result.allowParallelToolCalls)
    }

    @Test
    fun `stable tool call ids retain parallel execution`() {
        val result = ProviderPreflight.evaluate(
            request(
                ModelCapabilityProfile(
                    toolCalling = true,
                    parallelToolCalls = true,
                    toolCallIdStability = ToolCallIdStability.STABLE,
                    streaming = true,
                    reasoning = true,
                ),
                mode = AgentMode.AGENT,
                functionTools = 2,
            )
        )

        assertEquals(ProviderPreflightAction.ALLOW, result.action)
        assertTrue(result.allowParallelToolCalls)
        assertFalse(result.useClientGeneratedToolExecutionIdentity)
    }

    @Test
    fun `google style native and function tool conflict degrades explicitly`() {
        val result = ProviderPreflight.evaluate(
            request(
                ModelCapabilityProfile(
                    toolCalling = true,
                    providerNativeTools = true,
                    nativeToolsCompatibleWithFunctionTools = false,
                    streaming = true,
                    reasoning = true,
                ),
                functionTools = 1,
                nativeTools = 1,
            )
        )

        assertEquals(ProviderPreflightAction.DEGRADE, result.action)
        assertFalse(result.allowNativeTools)
        assertTrue(ProviderPreflightCode.NATIVE_TOOLS_DROPPED_FOR_FUNCTION_TOOLS in result.codes)
    }

    @Test
    fun `output reserve is clamped to provider maximum`() {
        val result = ProviderPreflight.evaluate(
            request(
                ModelCapabilityProfile(maxOutputTokens = 2_048, streaming = true, reasoning = true),
                requestedOutput = 4_096,
            )
        )

        assertEquals(ProviderPreflightAction.DEGRADE, result.action)
        assertEquals(2_048, result.outputTokens)
    }

    @Test
    fun `output reserve equal to context blocks`() {
        val result = ProviderPreflight.evaluate(
            request(ModelCapabilityProfile(contextWindowTokens = 4_096), reserve = 4_096)
        )

        assertEquals(ProviderPreflightAction.BLOCK, result.action)
        assertTrue(ProviderPreflightCode.OUTPUT_RESERVE_EXCEEDS_CONTEXT in result.codes)
    }

    @Test
    fun `output reserve is clamped before context viability is evaluated`() {
        val result = ProviderPreflight.evaluate(
            request(
                ModelCapabilityProfile(contextWindowTokens = 4_096, maxOutputTokens = 2_048),
                reserve = 4_096,
            )
        )

        assertEquals(ProviderPreflightAction.DEGRADE, result.action)
        assertEquals(2_048, result.outputTokens)
        assertTrue(ProviderPreflightCode.OUTPUT_RESERVE_CLAMPED in result.codes)
    }
}
