package me.rerere.rikkahub.data.ai.agent.routing

import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.ModelCapabilitySummary

/** All content-free facts needed to freeze a new AUTO run before it is persisted. */
data class NewAgentRunPlanRequest(
    val conversationId: String,
    val assistantId: String,
    val modelId: String,
    val providerId: String,
    val workspaceId: String?,
    val maxSteps: Int,
    val capabilitySummary: ModelCapabilitySummary,
    val decision: IntentDecision,
    val inputTrust: InputTrust,
    val resolvedToolNames: List<String>,
    val permissionDigest: String,
    /** Precomputed by the caller from execution-affecting settings; never pass setting bodies. */
    val executionContextDigest: String,
    val providerIdleTimeoutMillis: Long,
    val toolTimeoutMillis: Long,
    val runTimeoutMillis: Long,
)

/** Persisted entity identity plus current, precomputed facts used to validate a continuation. */
data class AgentRunContinuationRequest(
    val configSnapshotJson: String,
    val runId: String,
    val runConversationId: String,
    val runAssistantId: String,
    val parentRunId: String?,
    val runStatus: AgentRunStatus,
    val conversationId: String,
    val assistantId: String,
    val modelId: String,
    val providerId: String?,
    val workspaceId: String?,
    val capabilitySummary: ModelCapabilitySummary?,
    val availableToolNames: List<String>,
    val permissionDigest: String?,
    /** Precomputed by the caller; the planner compares the digest and never reads UI state. */
    val executionContextDigest: String?,
)

/** Complete immutable AUTO plan consumed by preflight, prompting, tool binding and AgentLoop. */
data class AgentRunPlan(
    val configSnapshot: AgentRunConfigSnapshot,
    val mode: AgentMode,
) {
    val routing: AgentRoutingSnapshot = requireNotNull(configSnapshot.routing)
}

enum class AgentRunPlanBlockReason {
    MALFORMED_SNAPSHOT,
    SNAPSHOT_TOO_LARGE,
    UNSUPPORTED_VERSION,
    INVALID_SNAPSHOT,
    CHILD_RUN,
    RUN_NOT_CONTINUABLE,
    RUN_CONVERSATION_MISMATCH,
    RUN_ASSISTANT_MISMATCH,
    CONVERSATION_DRIFT,
    ASSISTANT_DRIFT,
    MODEL_DRIFT,
    PROVIDER_DRIFT,
    WORKSPACE_DRIFT,
    CAPABILITY_DRIFT,
    PERMISSION_DIGEST_DRIFT,
    EXECUTION_CONTEXT_DRIFT,
    TOOL_MISSING,
}

sealed interface AgentRunContinuationResult {
    data class AutoReady(val plan: AgentRunPlan) : AgentRunContinuationResult

    /** Compatibility path only. It is never upgraded or re-routed as AUTO. */
    data class LegacyReady(
        val configSnapshot: AgentRunConfigSnapshot,
        val mode: AgentMode,
    ) : AgentRunContinuationResult

    data class Blocked(
        val reason: AgentRunPlanBlockReason,
        val missingToolNames: List<String> = emptyList(),
    ) : AgentRunContinuationResult
}

/** Pure planner: callers resolve models, tools and execution-context digests before invoking it. */
class AgentRunPlanner {
    fun planNewAuto(request: NewAgentRunPlanRequest): AgentRunPlan {
        require(request.conversationId.isNotBlank()) { "conversationId cannot be blank" }
        require(request.assistantId.isNotBlank()) { "assistantId cannot be blank" }
        require(request.modelId.isNotBlank()) { "modelId cannot be blank" }
        require(request.providerId.isNotBlank()) { "providerId cannot be blank" }
        require(request.workspaceId?.isNotBlank() != false) { "workspaceId cannot be blank" }
        require(request.maxSteps > 0) { "maxSteps must be positive" }
        require(request.decision.intent != AgentIntent.EXECUTE || request.inputTrust == InputTrust.USER_DIRECT) {
            "Derived input cannot authorize execution"
        }
        val routing = AgentRoutingSnapshot.create(
            intent = request.decision.intent,
            inputTrust = request.inputTrust,
            reasonCode = request.decision.reasonCode,
            resolvedToolNames = request.resolvedToolNames,
            permissionDigest = request.permissionDigest,
            executionContextDigest = request.executionContextDigest,
            providerIdleTimeoutMillis = request.providerIdleTimeoutMillis,
            toolTimeoutMillis = request.toolTimeoutMillis,
            runTimeoutMillis = request.runTimeoutMillis,
        )
        val config = AgentRunConfigSnapshot(
            runtimeVersion = AUTO_RUNTIME_VERSION,
            conversationId = request.conversationId,
            assistantId = request.assistantId,
            modelId = request.modelId,
            providerId = request.providerId,
            agentMode = null,
            maxSteps = request.maxSteps,
            workspaceId = request.workspaceId,
            toolPolicyVersion = AgentRoutingSnapshot.CURRENT_VERSION,
            toolDescriptors = emptyList(),
            capabilitySummary = request.capabilitySummary,
            routing = routing,
        )
        return AgentRunPlan(config, request.decision.intent.toCompatibilityMode())
    }

    fun restoreContinuation(request: AgentRunContinuationRequest): AgentRunContinuationResult {
        validateRunBoundary(request)?.let { return it }
        return when (val decoded = AgentRoutingSnapshotCodec.decode(request.configSnapshotJson)) {
            is AgentRoutingSnapshotDecodeResult.Invalid -> AgentRunContinuationResult.Blocked(
                reason = decoded.error.toBlockReason(),
            )

            is AgentRoutingSnapshotDecodeResult.Legacy -> restoreLegacy(decoded.config, request)
            is AgentRoutingSnapshotDecodeResult.Auto -> restoreAuto(decoded.config, decoded.routing, request)
        }
    }

    private fun validateRunBoundary(request: AgentRunContinuationRequest): AgentRunContinuationResult.Blocked? {
        if (request.runId.isBlank()) return blocked(AgentRunPlanBlockReason.INVALID_SNAPSHOT)
        if (request.parentRunId != null) return blocked(AgentRunPlanBlockReason.CHILD_RUN)
        if (request.runStatus !in CONTINUABLE_STATUSES) {
            return blocked(AgentRunPlanBlockReason.RUN_NOT_CONTINUABLE)
        }
        if (request.runConversationId.isBlank() || request.conversationId.isBlank()) {
            return blocked(AgentRunPlanBlockReason.RUN_CONVERSATION_MISMATCH)
        }
        if (request.runAssistantId.isBlank() || request.assistantId.isBlank()) {
            return blocked(AgentRunPlanBlockReason.RUN_ASSISTANT_MISMATCH)
        }
        return null
    }

    private fun restoreLegacy(
        config: AgentRunConfigSnapshot,
        request: AgentRunContinuationRequest,
    ): AgentRunContinuationResult {
        validatePersistedIdentity(config, request)?.let { return it }
        validateCurrentIdentity(config, request)?.let { return it }
        val mode = when (config.agentMode) {
            AgentMode.CHAT.name -> AgentMode.CHAT
            AgentMode.PLAN.name -> AgentMode.PLAN
            AgentMode.AGENT.name -> AgentMode.AGENT
            else -> return blocked(AgentRunPlanBlockReason.INVALID_SNAPSHOT)
        }
        return AgentRunContinuationResult.LegacyReady(config, mode)
    }

    private fun restoreAuto(
        config: AgentRunConfigSnapshot,
        routing: AgentRoutingSnapshot,
        request: AgentRunContinuationRequest,
    ): AgentRunContinuationResult {
        if (config.runtimeVersion != AUTO_RUNTIME_VERSION ||
            config.toolPolicyVersion != AgentRoutingSnapshot.CURRENT_VERSION
        ) {
            return blocked(AgentRunPlanBlockReason.UNSUPPORTED_VERSION)
        }
        if (!isCompleteAutoConfig(config)) return blocked(AgentRunPlanBlockReason.INVALID_SNAPSHOT)
        validatePersistedIdentity(config, request)?.let { return it }
        validateCurrentIdentity(config, request)?.let { return it }
        if (config.modelId != request.modelId) return blocked(AgentRunPlanBlockReason.MODEL_DRIFT)
        if (config.providerId != request.providerId) return blocked(AgentRunPlanBlockReason.PROVIDER_DRIFT)
        if (config.workspaceId != request.workspaceId) return blocked(AgentRunPlanBlockReason.WORKSPACE_DRIFT)
        if (config.capabilitySummary != request.capabilitySummary) {
            return blocked(AgentRunPlanBlockReason.CAPABILITY_DRIFT)
        }
        if (routing.permissionDigest != request.permissionDigest) {
            return blocked(AgentRunPlanBlockReason.PERMISSION_DIGEST_DRIFT)
        }
        if (routing.executionContextDigest != request.executionContextDigest) {
            return blocked(AgentRunPlanBlockReason.EXECUTION_CONTEXT_DRIFT)
        }
        val available = request.availableToolNames.filter(String::isNotBlank).toSet()
        val missing = routing.resolvedToolNames.filterNot(available::contains)
        if (missing.isNotEmpty()) {
            return AgentRunContinuationResult.Blocked(AgentRunPlanBlockReason.TOOL_MISSING, missing)
        }
        return AgentRunContinuationResult.AutoReady(
            AgentRunPlan(config.copy(routing = routing.normalized()), routing.intent.toCompatibilityMode()),
        )
    }

    private fun isCompleteAutoConfig(config: AgentRunConfigSnapshot): Boolean =
        config.conversationId?.isNotBlank() == true &&
            config.assistantId?.isNotBlank() == true &&
            config.modelId?.isNotBlank() == true &&
            config.providerId?.isNotBlank() == true &&
            config.workspaceId?.isNotBlank() != false &&
            config.maxSteps?.let { it > 0 } == true &&
            config.capabilitySummary != null &&
            config.agentMode == null &&
            config.toolDescriptors.isEmpty() &&
            config.routing != null

    private fun validatePersistedIdentity(
        config: AgentRunConfigSnapshot,
        request: AgentRunContinuationRequest,
    ): AgentRunContinuationResult.Blocked? {
        if (config.conversationId != null && config.conversationId != request.runConversationId) {
            return blocked(AgentRunPlanBlockReason.RUN_CONVERSATION_MISMATCH)
        }
        if (config.assistantId != null && config.assistantId != request.runAssistantId) {
            return blocked(AgentRunPlanBlockReason.RUN_ASSISTANT_MISMATCH)
        }
        return null
    }

    private fun validateCurrentIdentity(
        config: AgentRunConfigSnapshot,
        request: AgentRunContinuationRequest,
    ): AgentRunContinuationResult.Blocked? {
        if (request.runConversationId != request.conversationId ||
            config.conversationId != null && config.conversationId != request.conversationId
        ) {
            return blocked(AgentRunPlanBlockReason.CONVERSATION_DRIFT)
        }
        if (request.runAssistantId != request.assistantId ||
            config.assistantId != null && config.assistantId != request.assistantId
        ) {
            return blocked(AgentRunPlanBlockReason.ASSISTANT_DRIFT)
        }
        return null
    }

    private fun AgentRoutingSnapshotError.toBlockReason(): AgentRunPlanBlockReason = when (this) {
        AgentRoutingSnapshotError.MALFORMED_CONFIG -> AgentRunPlanBlockReason.MALFORMED_SNAPSHOT
        AgentRoutingSnapshotError.CONFIG_TOO_LARGE -> AgentRunPlanBlockReason.SNAPSHOT_TOO_LARGE
        AgentRoutingSnapshotError.UNSUPPORTED_VERSION -> AgentRunPlanBlockReason.UNSUPPORTED_VERSION
        AgentRoutingSnapshotError.INVALID_ROUTING,
        AgentRoutingSnapshotError.INVALID_LEGACY_MODE,
        -> AgentRunPlanBlockReason.INVALID_SNAPSHOT
    }

    private fun AgentIntent.toCompatibilityMode(): AgentMode = when (this) {
        AgentIntent.ANSWER -> AgentMode.CHAT
        AgentIntent.EXPLORE, AgentIntent.CLARIFY -> AgentMode.PLAN
        AgentIntent.EXECUTE -> AgentMode.AGENT
    }

    private fun blocked(reason: AgentRunPlanBlockReason) = AgentRunContinuationResult.Blocked(reason)

    companion object {
        const val AUTO_RUNTIME_VERSION = "agent-loop-v3"

        private val CONTINUABLE_STATUSES = setOf(
            AgentRunStatus.RUNNING,
            AgentRunStatus.WAITING_APPROVAL,
        )
    }
}
