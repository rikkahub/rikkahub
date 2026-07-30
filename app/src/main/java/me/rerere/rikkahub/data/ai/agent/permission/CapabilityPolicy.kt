package me.rerere.rikkahub.data.ai.agent.permission

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.isPlanModeBlockedTool
import me.rerere.rikkahub.data.ai.agent.subagent.ExploreToolAllowlist
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.workspace.Workspace

@Serializable
sealed class PolicyDecision {
    abstract val code: PolicyCode
    abstract val reason: String

    @Serializable
    data class Allow(
        override val code: PolicyCode,
        override val reason: String,
    ) : PolicyDecision()

    @Serializable
    data class Ask(
        override val code: PolicyCode,
        override val reason: String,
    ) : PolicyDecision()

    @Serializable
    data class Deny(
        override val code: PolicyCode,
        override val reason: String,
    ) : PolicyDecision()
}

@Serializable
enum class PolicyCode {
    PLAN_TOOL_NOT_ALLOWED,
    WORKSPACE_UNAVAILABLE,
    DESCRIPTOR_DENY,
    LEGACY_POLICY_ASK,
    MCP_TOOL_APPROVAL_REQUIRED,
    HIGH_RISK_OR_UNKNOWN,
    SIDE_EFFECT_REQUIRES_APPROVAL,
    DEFAULT_ASK,
    LOW_RISK_READ_ONLY,
    SUBAGENT_TOOL_NOT_ALLOWED,
}

/** MCP metadata is deliberately reduced to policy-relevant, non-secret fields. */
data class McpServerPolicyContext(
    val serverId: String? = null,
    val serverName: String? = null,
    val needsApproval: Boolean? = null,
)

data class CapabilityPolicyContext(
    val assistant: Assistant,
    val mode: AgentMode,
    val workspace: Workspace?,
    val descriptor: ToolDescriptor,
    val permissionPolicy: PermissionPolicy = PermissionPolicy.compatibleDefault(),
    val mcpServer: McpServerPolicyContext? = null,
    val isSubagentRun: Boolean = false,
)

/**
 * Pure permission evaluator. It never executes tools or reads persistent state, so it is safe to
 * call both before tool exposure and immediately before a tool call.
 */
object CapabilityPolicy {
    fun evaluate(context: CapabilityPolicyContext): PolicyDecision {
        val descriptor = context.descriptor

        // This guard is evaluated immediately before execution as well as at tool resolution.
        if (context.isSubagentRun && !ExploreToolAllowlist.isAllowed(descriptor.toolName)) {
            return deny(PolicyCode.SUBAGENT_TOOL_NOT_ALLOWED, "Controlled Explore runs permit repository reads only.")
        }

        if (context.mode == AgentMode.PLAN && isPlanModeBlockedTool(descriptor.toolName)) {
            return deny(PolicyCode.PLAN_TOOL_NOT_ALLOWED, "PLAN only permits its fixed read-only allowlist.")
        }
        if (descriptor.capability in workspaceCapabilities && context.workspace == null) {
            return deny(PolicyCode.WORKSPACE_UNAVAILABLE, "This tool requires an attached workspace.")
        }
        if (descriptor.defaultApproval == ToolDefaultApproval.DENY) {
            return deny(PolicyCode.DESCRIPTOR_DENY, "This tool is disabled by its descriptor.")
        }

        // Legacy policies may demand approval, but AUTO is never a grant over this policy's baseline.
        if (context.permissionPolicy.actionFor(descriptor.category) == ApprovalAction.ASK) {
            return ask(PolicyCode.LEGACY_POLICY_ASK, "Approval is required by the configured permission policy.")
        }
        if (descriptor.capability == ToolCapability.MCP && context.mcpServer?.needsApproval == true) {
            return ask(PolicyCode.MCP_TOOL_APPROVAL_REQUIRED, "The MCP tool requests approval.")
        }
        if (descriptor.riskLevel >= ToolRiskLevel.HIGH || descriptor.capability == ToolCapability.UNKNOWN) {
            return ask(PolicyCode.HIGH_RISK_OR_UNKNOWN, "High-risk or unknown tools require approval.")
        }
        if (descriptor.sideEffect != ToolSideEffect.NONE) {
            return ask(PolicyCode.SIDE_EFFECT_REQUIRES_APPROVAL, "Tools with side effects require approval.")
        }
        if (descriptor.defaultApproval == ToolDefaultApproval.ASK) {
            return ask(PolicyCode.DEFAULT_ASK, "The tool descriptor requires approval by default.")
        }
        return PolicyDecision.Allow(PolicyCode.LOW_RISK_READ_ONLY, "Low-risk read-only tool is allowed.")
    }

    private fun ask(code: PolicyCode, reason: String) = PolicyDecision.Ask(code, reason)

    private fun deny(code: PolicyCode, reason: String) = PolicyDecision.Deny(code, reason)

    private val workspaceCapabilities = setOf(
        ToolCapability.WORKSPACE_READ,
        ToolCapability.WORKSPACE_WRITE,
        ToolCapability.WORKSPACE_SHELL,
    )
}

/** Alias facade for callers that prefer an engine-oriented name. */
object PolicyEngine {
    fun evaluate(context: CapabilityPolicyContext): PolicyDecision = CapabilityPolicy.evaluate(context)
}
