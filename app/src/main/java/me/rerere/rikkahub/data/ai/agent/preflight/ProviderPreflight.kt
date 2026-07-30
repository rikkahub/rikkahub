package me.rerere.rikkahub.data.ai.agent.preflight

import me.rerere.ai.provider.ModelCapabilityProfile
import me.rerere.ai.provider.ToolCallIdStability
import me.rerere.rikkahub.data.ai.agent.AgentMode

enum class ProviderPreflightAction {
    ALLOW,
    DEGRADE,
    BLOCK,
}

enum class ProviderPreflightCode(val userMessage: String) {
    INVALID_CAPABILITY_PROFILE("The selected model has an invalid capability configuration."),
    OUTPUT_RESERVE_EXCEEDS_CONTEXT("The requested output reserve leaves no room for the conversation context."),
    FUNCTION_TOOLS_UNSUPPORTED("The selected model does not support the tools required by this chat."),
    UNSTABLE_TOOL_CALL_IDS("The selected model cannot provide stable tool-call IDs required for agent execution."),
    CLIENT_GENERATED_TOOL_EXECUTION_IDS("Provider tool-call IDs are unavailable; tools will run serially with client-generated execution identities."),
    PARALLEL_FUNCTION_TOOLS_DISABLED("Parallel function tools were disabled because provider tool-call IDs are not stable."),
    NATIVE_TOOLS_UNSUPPORTED("The selected model does not support its configured provider-native tools."),
    NATIVE_TOOLS_DROPPED_FOR_FUNCTION_TOOLS("Provider-native tools were disabled because they conflict with function tools."),
    REASONING_DISABLED("Reasoning was disabled because the selected model does not support it."),
    STREAMING_DISABLED("Streaming was disabled because the selected model does not support it."),
    OUTPUT_RESERVE_CLAMPED("The requested output reserve was reduced to the model maximum."),
    MULTIMODAL_INPUT_UNSUPPORTED("The selected model does not support the attached multimodal input."),
    STRUCTURED_OUTPUT_UNSUPPORTED("The selected model does not support JSON-schema structured output."),
}

data class ProviderPreflightRequest(
    val mode: AgentMode,
    val capabilities: ModelCapabilityProfile,
    val resolvedFunctionToolCount: Int,
    val configuredNativeToolCount: Int,
    val requestedOutputTokens: Int?,
    val outputReserveTokens: Int,
    val streamingRequested: Boolean,
    val reasoningRequested: Boolean,
    val multimodalInputRequested: Boolean,
    val structuredOutputRequested: Boolean = false,
)

data class ProviderPreflightResult(
    val action: ProviderPreflightAction,
    val codes: List<ProviderPreflightCode> = emptyList(),
    val allowFunctionTools: Boolean = true,
    val allowNativeTools: Boolean = true,
    val allowParallelToolCalls: Boolean = true,
    val useClientGeneratedToolExecutionIdentity: Boolean = false,
    val outputTokens: Int? = null,
    val streaming: Boolean = true,
    val reasoning: Boolean = true,
) {
    val userMessage: String
        get() = codes.joinToString(" ") { it.userMessage }
}

/**
 * Pure capability gate shared by interactive chat and future non-UI senders. It never mutates a
 * request: callers must apply the returned allow flags before invoking a provider.
 */
object ProviderPreflight {
    fun evaluate(request: ProviderPreflightRequest): ProviderPreflightResult {
        val profile = request.capabilities
        val outputReserveTokens = profile.maxOutputTokens?.let { maxOutput ->
            request.outputReserveTokens.coerceAtMost(maxOutput)
        } ?: request.outputReserveTokens
        val outputTokens = request.requestedOutputTokens?.let { requestedOutput ->
            profile.maxOutputTokens?.let(requestedOutput::coerceAtMost) ?: requestedOutput
        } ?: outputReserveTokens.takeIf { it != request.outputReserveTokens }
        val contextWindowTokens = profile.contextWindowTokens
        val blockCodes = buildList {
            if (profile.validationError() != null) add(ProviderPreflightCode.INVALID_CAPABILITY_PROFILE)
            if (outputReserveTokens <= 0 ||
                (contextWindowTokens != null && outputReserveTokens >= contextWindowTokens)
            ) {
                add(ProviderPreflightCode.OUTPUT_RESERVE_EXCEEDS_CONTEXT)
            }
            if (request.resolvedFunctionToolCount > 0 && !profile.toolCalling) {
                add(ProviderPreflightCode.FUNCTION_TOOLS_UNSUPPORTED)
            }
            if (request.resolvedFunctionToolCount > 0 && request.mode != AgentMode.CHAT &&
                profile.toolCallIdStability == ToolCallIdStability.UNSTABLE
            ) {
                add(ProviderPreflightCode.UNSTABLE_TOOL_CALL_IDS)
            }
            if (request.configuredNativeToolCount > 0 && !profile.providerNativeTools) {
                add(ProviderPreflightCode.NATIVE_TOOLS_UNSUPPORTED)
            }
            if (request.multimodalInputRequested && !profile.multimodalInput) {
                add(ProviderPreflightCode.MULTIMODAL_INPUT_UNSUPPORTED)
            }
            if (request.structuredOutputRequested && !profile.structuredOutputJsonSchema) {
                add(ProviderPreflightCode.STRUCTURED_OUTPUT_UNSUPPORTED)
            }
        }
        if (blockCodes.isNotEmpty()) {
            return ProviderPreflightResult(ProviderPreflightAction.BLOCK, codes = blockCodes)
        }

        val degradeCodes = mutableListOf<ProviderPreflightCode>()
        if (outputReserveTokens != request.outputReserveTokens || outputTokens != request.requestedOutputTokens) {
            degradeCodes += ProviderPreflightCode.OUTPUT_RESERVE_CLAMPED
        }
        val streaming = request.streamingRequested && profile.streaming
        if (request.streamingRequested && !streaming) degradeCodes += ProviderPreflightCode.STREAMING_DISABLED
        val reasoning = request.reasoningRequested && profile.reasoning
        if (request.reasoningRequested && !reasoning) degradeCodes += ProviderPreflightCode.REASONING_DISABLED
        val allowNativeTools = !(request.resolvedFunctionToolCount > 0 && request.configuredNativeToolCount > 0 &&
            !profile.nativeToolsCompatibleWithFunctionTools)
        if (!allowNativeTools) degradeCodes += ProviderPreflightCode.NATIVE_TOOLS_DROPPED_FOR_FUNCTION_TOOLS
        val useClientGeneratedToolExecutionIdentity = request.resolvedFunctionToolCount > 0 &&
            profile.toolCallIdStability == ToolCallIdStability.UNKNOWN
        if (useClientGeneratedToolExecutionIdentity) {
            degradeCodes += ProviderPreflightCode.CLIENT_GENERATED_TOOL_EXECUTION_IDS
        }
        val allowParallelToolCalls = profile.parallelToolCalls &&
            profile.toolCallIdStability == ToolCallIdStability.STABLE
        if (request.resolvedFunctionToolCount > 1 && !allowParallelToolCalls) {
            degradeCodes += ProviderPreflightCode.PARALLEL_FUNCTION_TOOLS_DISABLED
        }

        return ProviderPreflightResult(
            action = if (degradeCodes.isEmpty()) ProviderPreflightAction.ALLOW else ProviderPreflightAction.DEGRADE,
            codes = degradeCodes,
            allowFunctionTools = request.resolvedFunctionToolCount == 0 || profile.toolCalling,
            allowNativeTools = allowNativeTools,
            allowParallelToolCalls = allowParallelToolCalls,
            useClientGeneratedToolExecutionIdentity = useClientGeneratedToolExecutionIdentity,
            outputTokens = outputTokens,
            streaming = streaming,
            reasoning = reasoning,
        )
    }
}
