package me.rerere.rikkahub.data.ai.agent.routing

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.agent.permission.ApprovalAction
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.permission.ToolCapability
import me.rerere.rikkahub.data.ai.agent.permission.ToolCategory
import me.rerere.rikkahub.data.ai.agent.permission.ToolDefaultApproval
import me.rerere.rikkahub.data.ai.agent.permission.ToolSideEffect
import me.rerere.rikkahub.data.ai.agent.subagent.ExploreToolAllowlist
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry

data class ToolProfileRequest(
    val intent: AgentIntent,
    val inputTrust: InputTrust,
    val assistantId: String,
    val workspaceId: String? = null,
    val permissionPolicy: PermissionPolicy = PermissionPolicy.compatibleDefault(),
    val defaultToolTimeoutMillis: Long,
    val disabledToolNames: Set<String> = emptySet(),
    val hardAllowedToolNames: Set<String>? = null,
    val isSubagentRun: Boolean = false,
    val policyVersion: String = CURRENT_TOOL_PROFILE_POLICY_VERSION,
) {
    init {
        require(assistantId.isNotBlank()) { "assistantId cannot be blank" }
        require(defaultToolTimeoutMillis > 0) { "defaultToolTimeoutMillis must be positive" }
        require(disabledToolNames.none(String::isBlank)) { "Disabled tool names cannot be blank" }
        require(hardAllowedToolNames?.none(String::isBlank) != false) { "Allowed tool names cannot be blank" }
        require(policyVersion.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid tool profile policy version" }
    }
}

data class ResolvedToolProfile(
    /** Provider order is retained for the model request. */
    val tools: List<DescribedTool>,
    /** Snapshot order is canonical and independent from provider registration order. */
    val resolvedToolNames: List<String>,
    val permissionDigest: String,
    val effectiveIntent: AgentIntent,
)

class ToolNameCollisionException(
    val collidingNames: List<String>,
) : IllegalStateException("Colliding tool names: ${collidingNames.joinToString(", ")}")

class ToolDescriptorNameMismatchException(
    val toolName: String,
    val descriptorName: String,
) : IllegalStateException("Tool descriptor name '$descriptorName' does not match '$toolName'")

class FrozenToolProfileMismatchException(
    val missingToolNames: List<String> = emptyList(),
    val permissionDigestChanged: Boolean = false,
) : IllegalStateException(
    when {
        missingToolNames.isNotEmpty() -> "Frozen tools are unavailable: ${missingToolNames.joinToString(", ")}"
        permissionDigestChanged -> "Frozen tool permission policy changed"
        else -> "Frozen tool profile changed"
    },
)

/**
 * Pure, deterministic AUTO toolset resolver. Provider discovery remains in [ToolRegistry]; this
 * class only validates, filters, canonicalizes and binds policy facts into a content-free digest.
 */
class ToolProfileResolver {
    fun resolve(
        candidates: List<DescribedTool>,
        request: ToolProfileRequest,
    ): ResolvedToolProfile = resolveInternal(candidates, request, frozenToolNames = null)

    /** Extra tools discovered after the Run was frozen are ignored; missing or changed tools fail closed. */
    fun resolveFrozen(
        candidates: List<DescribedTool>,
        snapshot: AgentRoutingSnapshot,
        request: ToolProfileRequest,
    ): ResolvedToolProfile {
        require(request.intent == snapshot.intent) { "Frozen intent does not match the resolver request" }
        require(request.inputTrust == snapshot.inputTrust) { "Frozen input trust does not match the resolver request" }
        require(request.defaultToolTimeoutMillis == snapshot.toolTimeoutMillis) {
            "Frozen tool timeout does not match the resolver request"
        }
        val profile = resolveInternal(candidates, request, snapshot.resolvedToolNames.toSet())
        val missing = snapshot.resolvedToolNames.filterNot(profile.resolvedToolNames::contains)
        if (missing.isNotEmpty()) throw FrozenToolProfileMismatchException(missingToolNames = missing)
        if (profile.permissionDigest != snapshot.permissionDigest) {
            throw FrozenToolProfileMismatchException(permissionDigestChanged = true)
        }
        return profile
    }

    private fun resolveInternal(
        candidates: List<DescribedTool>,
        request: ToolProfileRequest,
        frozenToolNames: Set<String>?,
    ): ResolvedToolProfile {
        val validationCandidates = if (frozenToolNames == null) {
            candidates
        } else {
            val frozenCollisionKeys = frozenToolNames.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
            candidates.filter { it.tool.name.lowercase(Locale.ROOT) in frozenCollisionKeys }
        }
        validateCandidates(validationCandidates)
        val effectiveIntent = effectiveIntent(request.intent, request.inputTrust)
        val tools = candidates.filter { described ->
            val name = described.tool.name
            name !in request.disabledToolNames &&
                (request.hardAllowedToolNames == null || name in request.hardAllowedToolNames) &&
                (!request.isSubagentRun || ExploreToolAllowlist.isAllowed(name)) &&
                described.descriptor.defaultApproval != ToolDefaultApproval.DENY &&
                isAllowedForIntent(described, effectiveIntent) &&
                (frozenToolNames == null || name in frozenToolNames)
        }
        val names = AgentRoutingSnapshot.canonicalToolNames(tools.map { it.tool.name })
        return ResolvedToolProfile(
            tools = tools,
            resolvedToolNames = names,
            permissionDigest = permissionDigest(tools, request, effectiveIntent),
            effectiveIntent = effectiveIntent,
        )
    }

    internal fun validateCandidates(candidates: List<DescribedTool>) {
        candidates.forEach { described ->
            if (described.tool.name != described.descriptor.toolName) {
                throw ToolDescriptorNameMismatchException(described.tool.name, described.descriptor.toolName)
            }
        }
        // Function-name handling is not consistently case-sensitive across providers. Reject both
        // exact duplicates and ASCII case-folded aliases instead of selecting a provider by order.
        val collisions = candidates
            .groupBy { it.tool.name.lowercase(Locale.ROOT) }
            .values
            .filter { it.size > 1 }
            .flatMap { group -> group.map { it.tool.name } }
            .distinct()
            .sorted()
        if (collisions.isNotEmpty()) throw ToolNameCollisionException(collisions)
    }

    private fun effectiveIntent(intent: AgentIntent, inputTrust: InputTrust): AgentIntent =
        if (intent == AgentIntent.EXECUTE && inputTrust == InputTrust.DERIVED_UNTRUSTED) {
            AgentIntent.EXPLORE
        } else {
            intent
        }

    private fun isAllowedForIntent(tool: DescribedTool, intent: AgentIntent): Boolean {
        val descriptor = tool.descriptor
        val allowedCapabilities = when (intent) {
            AgentIntent.ANSWER -> answerCapabilities
            AgentIntent.CLARIFY -> clarifyCapabilities
            AgentIntent.EXPLORE -> exploreCapabilities
            AgentIntent.EXECUTE -> return true
        }
        return descriptor.capability in allowedCapabilities && descriptor.sideEffect == ToolSideEffect.NONE
    }

    private fun permissionDigest(
        tools: List<DescribedTool>,
        request: ToolProfileRequest,
        effectiveIntent: AgentIntent,
    ): String {
        val actions = ToolCategory.entries
            .sortedBy(ToolCategory::name)
            .map { category ->
                CanonicalCategoryAction(category.name, request.permissionPolicy.actionFor(category).name)
            }
        val canonicalTools = tools.sortedWith(compareBy({ it.tool.name.lowercase(Locale.ROOT) }, { it.tool.name }))
            .map { described ->
                val descriptor = described.descriptor
                CanonicalToolPermission(
                    name = described.tool.name,
                    capability = descriptor.capability.name,
                    category = descriptor.category.name,
                    riskLevel = descriptor.riskLevel.name,
                    sideEffect = descriptor.sideEffect.name,
                    dataScope = descriptor.dataScope.name,
                    networkScope = descriptor.networkScope.name,
                    effectiveTimeoutMillis = descriptor.timeoutMillis ?: request.defaultToolTimeoutMillis,
                    idempotency = descriptor.idempotency.name,
                    replayPolicy = descriptor.replayPolicy.name,
                    defaultApproval = descriptor.defaultApproval.name,
                    redactionPolicy = descriptor.redactionPolicy.name,
                    configuredNeedsApproval = described.approvalPolicy?.configuredNeedsApproval,
                    mcpServerId = described.mcpServer?.serverId,
                    mcpNeedsApproval = described.mcpServer?.needsApproval,
                )
            }
        canonicalTools.forEach {
            require(it.effectiveTimeoutMillis > 0) { "Tool timeout must be positive: ${it.name}" }
        }
        val payload = PermissionDigestPayload(
            schemaVersion = PERMISSION_DIGEST_VERSION,
            policyVersion = request.policyVersion,
            routingVersion = AgentRoutingSnapshot.CURRENT_VERSION,
            effectiveIntent = effectiveIntent.name,
            inputTrust = request.inputTrust.name,
            assistantId = request.assistantId,
            workspaceId = request.workspaceId,
            isSubagentRun = request.isSubagentRun,
            injectPromptSummary = request.permissionPolicy.injectPromptSummary,
            actions = actions,
            tools = canonicalTools,
        )
        val encoded = canonicalJson.encodeToString(PermissionDigestPayload.serializer(), payload)
        return "sha256:${encoded.sha256()}"
    }

    private companion object {
        const val PERMISSION_DIGEST_VERSION = "tool-profile-v1"

        val answerCapabilities = setOf(
            ToolCapability.LOCAL_READ,
            ToolCapability.USER_INTERACTION,
        )
        val clarifyCapabilities = setOf(ToolCapability.USER_INTERACTION)
        val exploreCapabilities = setOf(
            ToolCapability.SEARCH,
            ToolCapability.LOCAL_READ,
            ToolCapability.CONVERSATION_READ,
            ToolCapability.WORKSPACE_READ,
            ToolCapability.SKILL_READ,
            ToolCapability.USER_INTERACTION,
            ToolCapability.SUBAGENT_READ,
        )
        val canonicalJson = Json { encodeDefaults = true }
    }
}

@Serializable
private data class PermissionDigestPayload(
    val schemaVersion: String,
    val policyVersion: String,
    val routingVersion: String,
    val effectiveIntent: String,
    val inputTrust: String,
    val assistantId: String,
    val workspaceId: String?,
    val isSubagentRun: Boolean,
    val injectPromptSummary: Boolean,
    val actions: List<CanonicalCategoryAction>,
    val tools: List<CanonicalToolPermission>,
)

@Serializable
private data class CanonicalCategoryAction(
    val category: String,
    val action: String,
)

@Serializable
private data class CanonicalToolPermission(
    val name: String,
    val capability: String,
    val category: String,
    val riskLevel: String,
    val sideEffect: String,
    val dataScope: String,
    val networkScope: String,
    val effectiveTimeoutMillis: Long,
    val idempotency: String,
    val replayPolicy: String,
    val defaultApproval: String,
    val redactionPolicy: String,
    val configuredNeedsApproval: Boolean?,
    val mcpServerId: String?,
    val mcpNeedsApproval: Boolean?,
)

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

const val CURRENT_TOOL_PROFILE_POLICY_VERSION = AgentRoutingSnapshot.CURRENT_VERSION
