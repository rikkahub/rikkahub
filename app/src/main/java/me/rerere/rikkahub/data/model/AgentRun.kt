package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ModelCapabilityProfile
import me.rerere.ai.provider.ToolCallIdStability
import me.rerere.rikkahub.data.ai.agent.routing.AgentRoutingSnapshot

enum class AgentRunStatus {
    QUEUED,
    PREFLIGHT,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    BLOCKED,
    ;

    val isActive: Boolean
        get() = this in ACTIVE

    val isTerminal: Boolean
        get() = !isActive

    companion object {
        val ACTIVE = setOf(QUEUED, PREFLIGHT, RUNNING, WAITING_APPROVAL)
    }
}

enum class AgentStepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,
    ;

    val isActive: Boolean
        get() = this in ACTIVE

    companion object {
        val ACTIVE = setOf(PENDING, RUNNING)
    }
}

enum class ToolExecutionStatus {
    PENDING,
    WAITING_APPROVAL,
    AUTHORIZED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DENIED,
    CANCELLED,
    UNKNOWN_AFTER_INTERRUPT,
    ;

    val isActive: Boolean
        get() = this in ACTIVE

    companion object {
        val ACTIVE = setOf(PENDING, WAITING_APPROVAL, AUTHORIZED, RUNNING)
    }
}

enum class AgentApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    CANCELLED,
}

/** Resolving an expired approval creates a replacement that must be bound to the same tool card. */
data class ApprovalResolution(
    val resolved: Boolean = false,
    val replacementApprovalId: String? = null,
)

/**
 * Deliberately excludes prompts, messages, tool arguments, and tool output. It is a reproducibility hint only.
 */
@Serializable
data class AgentRunConfigSnapshot(
    val runtimeVersion: String? = null,
    val conversationId: String? = null,
    val assistantId: String? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val agentMode: String? = null,
    val maxSteps: Int? = null,
    val workspaceId: String? = null,
    val toolPolicyVersion: String? = null,
    val toolDescriptors: List<String> = emptyList(),
    val budgetPlaceholder: String? = null,
    /** Present only for controlled Explore children; it contains limits, never prompts or tool bodies. */
    val childBudget: ChildRunBudgetSnapshot? = null,
    val capabilitySummary: ModelCapabilitySummary? = null,
    /** Null only for runs created before AUTO intent routing was introduced. */
    val routing: AgentRoutingSnapshot? = null,
)

@Serializable
data class ChildRunBudgetSnapshot(
    val maxSteps: Int,
    val maxToolCalls: Int,
    val maxOutputTokens: Int,
    val maxDurationMillis: Long,
    val maxContextTokens: Int = 16 * 1024,
) {
    init {
        require(maxSteps > 0 && maxToolCalls > 0 && maxOutputTokens > 0)
        require(maxDurationMillis > 0 && maxContextTokens > 0)
    }
}

/** The only child result persisted or returned to a parent. Tool inputs, outputs, and traces are excluded. */
@Serializable
data class ChildRunReport(
    val findings: List<String> = emptyList(),
    val evidencePaths: List<String> = emptyList(),
    val confidence: String = "LOW",
    val unresolved: List<String> = emptyList(),
)

/** Persisted capability facts only; provider credentials, headers, and request bodies are excluded. */
@Serializable
data class ModelCapabilitySummary(
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val toolCalling: Boolean = false,
    val parallelToolCalls: Boolean = false,
    val structuredOutputJsonSchema: Boolean = false,
    val streaming: Boolean = false,
    val reasoning: Boolean = false,
    val multimodalInput: Boolean = false,
    val multimodalOutput: Boolean = false,
    val providerNativeTools: Boolean = false,
    val nativeToolsCompatibleWithFunctionTools: Boolean = false,
    val toolCallIdStability: ToolCallIdStability = ToolCallIdStability.UNKNOWN,
)

fun ModelCapabilityProfile.toSnapshotSummary() = ModelCapabilitySummary(
    contextWindowTokens = contextWindowTokens,
    maxOutputTokens = maxOutputTokens,
    toolCalling = toolCalling,
    parallelToolCalls = parallelToolCalls,
    structuredOutputJsonSchema = structuredOutputJsonSchema,
    streaming = streaming,
    reasoning = reasoning,
    multimodalInput = multimodalInput,
    multimodalOutput = multimodalOutput,
    providerNativeTools = providerNativeTools,
    nativeToolsCompatibleWithFunctionTools = nativeToolsCompatibleWithFunctionTools,
    toolCallIdStability = toolCallIdStability,
)

@Serializable
data class AgentRunError(
    val code: String,
    val category: String? = null,
    val retryable: Boolean? = null,
)

@Serializable
data class AgentRunSummary(
    val completedSteps: Int? = null,
    val completedToolExecutions: Int? = null,
    val outcome: String? = null,
    val contextPlan: me.rerere.rikkahub.data.ai.agent.context.ContextPlan? = null,
    val childReport: ChildRunReport? = null,
)

@Serializable
data class AgentStepSummary(
    val kind: String,
    val detail: String? = null,
)

@Serializable
data class ToolExecutionSummary(
    val category: String? = null,
    val operation: String? = null,
    val targetType: String? = null,
    val toolCallId: String? = null,
    val inputSha256: String? = null,
    val inputBytes: Int? = null,
    val outputSha256: String? = null,
    val outputBytes: Int? = null,
    val outputPreview: String? = null,
    val outputMimeType: String? = null,
    val outputArtifactId: String? = null,
)

@Serializable
data class AgentApprovalSummary(
    val policySource: String? = null,
    val reasonCode: String? = null,
    val stepId: String? = null,
    val stepSequence: Int? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val inputSha256: String? = null,
    val assistantId: String? = null,
    val workspaceId: String? = null,
    val mode: String? = null,
    val policyDigest: String? = null,
    val expiresAt: Long? = null,
)
