package me.rerere.rikkahub.data.ai.agent.permission

import kotlinx.serialization.Serializable
import me.rerere.ai.core.Tool

/** Stable, serializable metadata used to make tool permission decisions. */
@Serializable
data class ToolDescriptor(
    val toolName: String,
    val capability: ToolCapability,
    val category: ToolCategory,
    val riskLevel: ToolRiskLevel,
    val sideEffect: ToolSideEffect,
    val dataScope: ToolDataScope,
    val networkScope: ToolNetworkScope,
    val timeoutMillis: Long? = null,
    val idempotency: ToolIdempotency,
    val replayPolicy: ToolReplayPolicy,
    val defaultApproval: ToolDefaultApproval,
    val redactionPolicy: ToolRedactionPolicy,
) {
    companion object {
        fun unknown(toolName: String) = ToolDescriptor(
            toolName = toolName,
            capability = ToolCapability.UNKNOWN,
            category = ToolCategory.UNKNOWN,
            riskLevel = ToolRiskLevel.HIGH,
            sideEffect = ToolSideEffect.UNKNOWN,
            dataScope = ToolDataScope.UNKNOWN,
            networkScope = ToolNetworkScope.UNKNOWN,
            idempotency = ToolIdempotency.UNKNOWN,
            replayPolicy = ToolReplayPolicy.UNKNOWN,
            defaultApproval = ToolDefaultApproval.ASK,
            redactionPolicy = ToolRedactionPolicy.REDACT_ALL,
        )
    }
}

@Serializable
enum class ToolCapability {
    SEARCH,
    LOCAL_READ,
    LOCAL_WRITE,
    CONVERSATION_READ,
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    WORKSPACE_SHELL,
    SKILL_READ,
    MEMORY_MUTATION,
    USER_INTERACTION,
    SUBAGENT_READ,
    MCP,
    UNKNOWN,
}

@Serializable
enum class ToolRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
enum class ToolSideEffect { NONE, LOCAL_WRITE, WORKSPACE_WRITE, PROCESS_EXECUTION, EXTERNAL, UNKNOWN }

@Serializable
enum class ToolDataScope { NONE, PUBLIC, CONVERSATION, LOCAL_SENSITIVE, WORKSPACE, MEMORY, UNKNOWN }

@Serializable
enum class ToolNetworkScope { NONE, INTERNET, MCP, UNKNOWN }

@Serializable
enum class ToolIdempotency { IDEMPOTENT, CONDITIONAL, NON_IDEMPOTENT, UNKNOWN }

@Serializable
enum class ToolReplayPolicy { IDEMPOTENT, REPLAY_SAFE, REQUIRES_APPROVAL, NEVER_REPLAY, UNKNOWN }

@Serializable
enum class ToolDefaultApproval { ALLOW, ASK, DENY }

@Serializable
enum class ToolRedactionPolicy { NONE, REDACT_SENSITIVE, REDACT_ALL }

data class DescribedTool(
    val tool: Tool,
    val descriptor: ToolDescriptor,
    val mcpServer: McpServerPolicyContext? = null,
)

/**
 * The adapter boundary for the legacy [Tool] API. Providers can continue returning [Tool] while
 * policy-aware callers receive a descriptor for every tool, including future or third-party tools.
 */
object ToolDescriptorRegistry {
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    private val descriptors = listOf(
        descriptor("search_web", ToolCapability.SEARCH, ToolCategory.SEARCH, ToolRiskLevel.LOW,
            ToolDataScope.PUBLIC, ToolNetworkScope.INTERNET, ToolReplayPolicy.REPLAY_SAFE, ToolDefaultApproval.ALLOW),
        descriptor("scrape_web", ToolCapability.SEARCH, ToolCategory.SEARCH, ToolRiskLevel.LOW,
            ToolDataScope.PUBLIC, ToolNetworkScope.INTERNET, ToolReplayPolicy.REPLAY_SAFE, ToolDefaultApproval.ALLOW),
        descriptor("get_time_info", ToolCapability.LOCAL_READ, ToolCategory.LOCAL_SAFE, ToolRiskLevel.LOW,
            ToolDataScope.NONE, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ALLOW),
        descriptor("text_to_speech", ToolCapability.LOCAL_WRITE, ToolCategory.LOCAL_SAFE, ToolRiskLevel.LOW,
            ToolDataScope.NONE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK,
            ToolSideEffect.EXTERNAL),
        descriptor("ask_user", ToolCapability.USER_INTERACTION, ToolCategory.LOCAL_SENSITIVE, ToolRiskLevel.MEDIUM,
            ToolDataScope.LOCAL_SENSITIVE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK),
        descriptor("clipboard_tool", ToolCapability.LOCAL_WRITE, ToolCategory.LOCAL_SENSITIVE, ToolRiskLevel.HIGH,
            ToolDataScope.LOCAL_SENSITIVE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK,
            ToolSideEffect.LOCAL_WRITE),
        descriptor("calendar_query", ToolCapability.LOCAL_READ, ToolCategory.LOCAL_SENSITIVE, ToolRiskLevel.HIGH,
            ToolDataScope.LOCAL_SENSITIVE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK),
        descriptor("calendar_create", ToolCapability.LOCAL_WRITE, ToolCategory.LOCAL_SENSITIVE, ToolRiskLevel.HIGH,
            ToolDataScope.LOCAL_SENSITIVE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK,
            ToolSideEffect.LOCAL_WRITE),
        descriptor("get_screen_time", ToolCapability.LOCAL_READ, ToolCategory.LOCAL_SENSITIVE, ToolRiskLevel.HIGH,
            ToolDataScope.LOCAL_SENSITIVE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK),
        descriptor("eval_javascript", ToolCapability.LOCAL_WRITE, ToolCategory.LOCAL_SENSITIVE, ToolRiskLevel.HIGH,
            ToolDataScope.LOCAL_SENSITIVE, ToolNetworkScope.NONE, ToolReplayPolicy.NEVER_REPLAY, ToolDefaultApproval.ASK,
            ToolSideEffect.PROCESS_EXECUTION),
        descriptor("recent_chats", ToolCapability.CONVERSATION_READ, ToolCategory.CONVERSATION, ToolRiskLevel.LOW,
            ToolDataScope.CONVERSATION, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ALLOW),
        descriptor("conversation_search", ToolCapability.CONVERSATION_READ, ToolCategory.CONVERSATION, ToolRiskLevel.LOW,
            ToolDataScope.CONVERSATION, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ALLOW),
        descriptor("workspace_read_file", ToolCapability.WORKSPACE_READ, ToolCategory.WORKSPACE_READ, ToolRiskLevel.LOW,
            ToolDataScope.WORKSPACE, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ALLOW),
        descriptor("workspace_search_files", ToolCapability.WORKSPACE_READ, ToolCategory.WORKSPACE_READ, ToolRiskLevel.LOW,
            ToolDataScope.WORKSPACE, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ALLOW),
        descriptor("artifact_read", ToolCapability.CONVERSATION_READ, ToolCategory.CONVERSATION, ToolRiskLevel.LOW,
            ToolDataScope.CONVERSATION, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ALLOW),
        descriptor("artifact_search", ToolCapability.CONVERSATION_READ, ToolCategory.CONVERSATION, ToolRiskLevel.LOW,
            ToolDataScope.CONVERSATION, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ALLOW),
        descriptor("workspace_write_file", ToolCapability.WORKSPACE_WRITE, ToolCategory.WORKSPACE_WRITE, ToolRiskLevel.HIGH,
            ToolDataScope.WORKSPACE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK,
            ToolSideEffect.WORKSPACE_WRITE),
        descriptor("workspace_edit_file", ToolCapability.WORKSPACE_WRITE, ToolCategory.WORKSPACE_WRITE, ToolRiskLevel.HIGH,
            ToolDataScope.WORKSPACE, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK,
            ToolSideEffect.WORKSPACE_WRITE),
        descriptor("workspace_shell", ToolCapability.WORKSPACE_SHELL, ToolCategory.WORKSPACE_SHELL, ToolRiskLevel.CRITICAL,
            ToolDataScope.WORKSPACE, ToolNetworkScope.NONE, ToolReplayPolicy.NEVER_REPLAY, ToolDefaultApproval.ASK,
            ToolSideEffect.PROCESS_EXECUTION),
        descriptor("memory_tool", ToolCapability.MEMORY_MUTATION, ToolCategory.MEMORY, ToolRiskLevel.HIGH,
            ToolDataScope.MEMORY, ToolNetworkScope.NONE, ToolReplayPolicy.REQUIRES_APPROVAL, ToolDefaultApproval.ASK,
            ToolSideEffect.LOCAL_WRITE),
        descriptor("use_skill", ToolCapability.SKILL_READ, ToolCategory.SKILL, ToolRiskLevel.MEDIUM,
            ToolDataScope.LOCAL_SENSITIVE, ToolNetworkScope.NONE, ToolReplayPolicy.IDEMPOTENT, ToolDefaultApproval.ASK),
        descriptor("explore_subagent", ToolCapability.SUBAGENT_READ, ToolCategory.LOCAL_SAFE, ToolRiskLevel.LOW,
            ToolDataScope.WORKSPACE, ToolNetworkScope.NONE, ToolReplayPolicy.REPLAY_SAFE, ToolDefaultApproval.ALLOW),
    ).associateBy(ToolDescriptor::toolName)

    fun descriptorFor(tool: Tool): ToolDescriptor = descriptorFor(tool.name)

    fun descriptorFor(toolName: String): ToolDescriptor = descriptors[toolName]
        ?: if (toolName.startsWith("mcp__")) {
            descriptor(
                toolName = toolName,
                capability = ToolCapability.MCP,
                category = ToolCategory.MCP,
                riskLevel = ToolRiskLevel.HIGH,
                dataScope = ToolDataScope.UNKNOWN,
                networkScope = ToolNetworkScope.MCP,
                replayPolicy = ToolReplayPolicy.UNKNOWN,
                defaultApproval = ToolDefaultApproval.ASK,
                sideEffect = ToolSideEffect.UNKNOWN,
                redactionPolicy = ToolRedactionPolicy.REDACT_ALL,
            )
        } else {
            ToolDescriptor.unknown(toolName)
        }

    private fun descriptor(
        toolName: String,
        capability: ToolCapability,
        category: ToolCategory,
        riskLevel: ToolRiskLevel,
        dataScope: ToolDataScope,
        networkScope: ToolNetworkScope,
        replayPolicy: ToolReplayPolicy,
        defaultApproval: ToolDefaultApproval,
        sideEffect: ToolSideEffect = ToolSideEffect.NONE,
        redactionPolicy: ToolRedactionPolicy = ToolRedactionPolicy.REDACT_SENSITIVE,
    ) = ToolDescriptor(
        toolName = toolName,
        capability = capability,
        category = category,
        riskLevel = riskLevel,
        sideEffect = sideEffect,
        dataScope = dataScope,
        networkScope = networkScope,
        timeoutMillis = DEFAULT_TIMEOUT_MS,
        idempotency = idempotencyFor(replayPolicy),
        replayPolicy = replayPolicy,
        defaultApproval = defaultApproval,
        redactionPolicy = redactionPolicy,
    )

    private fun idempotencyFor(replayPolicy: ToolReplayPolicy): ToolIdempotency = when (replayPolicy) {
        ToolReplayPolicy.IDEMPOTENT, ToolReplayPolicy.REPLAY_SAFE -> ToolIdempotency.IDEMPOTENT
        ToolReplayPolicy.REQUIRES_APPROVAL -> ToolIdempotency.CONDITIONAL
        ToolReplayPolicy.NEVER_REPLAY -> ToolIdempotency.NON_IDEMPOTENT
        ToolReplayPolicy.UNKNOWN -> ToolIdempotency.UNKNOWN
    }
}
