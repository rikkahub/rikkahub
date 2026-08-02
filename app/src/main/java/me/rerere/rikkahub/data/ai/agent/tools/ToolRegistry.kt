package me.rerere.rikkahub.data.ai.agent.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.AgentRoutingSnapshot
import me.rerere.rikkahub.data.ai.agent.routing.ResolvedToolProfile
import me.rerere.rikkahub.data.ai.agent.routing.ToolProfileRequest
import me.rerere.rikkahub.data.ai.agent.routing.ToolProfileResolver
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust

class ToolRegistry(
    private val providers: List<ToolProvider>,
    private val profileResolver: ToolProfileResolver = ToolProfileResolver(),
) {
    /** 按 [ToolProvider.order] 解析并合并工具列表。 */
    suspend fun resolve(ctx: ToolResolveContext): List<Tool> {
        return resolveWithDescriptors(ctx).map(DescribedTool::tool)
    }

    /** Policy-aware counterpart to [resolve], retaining descriptors for each resolved tool. */
    suspend fun resolveWithDescriptors(ctx: ToolResolveContext): List<DescribedTool> {
        val tools = discoverWithDescriptors(ctx)
        profileResolver.validateCandidates(tools)

        return tools
    }

    /** Provider discovery without legacy mode filtering; AUTO profiles are resolved afterwards. */
    suspend fun discoverWithDescriptors(ctx: ToolResolveContext): List<DescribedTool> = providers
        .sortedBy { it.order }
        .filter { it.isEnabled(ctx) }
        .flatMap { it.provideWithDescriptors(ctx) }

    suspend fun resolveProfile(
        ctx: ToolResolveContext,
        intent: AgentIntent,
        inputTrust: InputTrust,
        defaultToolTimeoutMillis: Long,
        disabledToolNames: Set<String> = emptySet(),
        hardAllowedToolNames: Set<String>? = null,
    ): ResolvedToolProfile = profileResolver.resolve(
        discoverAutoCandidates(ctx),
        profileRequest(
            ctx = ctx,
            intent = intent,
            inputTrust = inputTrust,
            defaultToolTimeoutMillis = defaultToolTimeoutMillis,
            disabledToolNames = disabledToolNames,
            hardAllowedToolNames = hardAllowedToolNames,
        ),
    )

    suspend fun resolveFrozenProfile(
        ctx: ToolResolveContext,
        snapshot: AgentRoutingSnapshot,
        disabledToolNames: Set<String> = emptySet(),
        hardAllowedToolNames: Set<String>? = null,
    ): ResolvedToolProfile = profileResolver.resolveFrozen(
        discoverAutoCandidates(ctx),
        snapshot,
        profileRequest(
            ctx = ctx,
            intent = snapshot.intent,
            inputTrust = snapshot.inputTrust,
            defaultToolTimeoutMillis = snapshot.toolTimeoutMillis,
            disabledToolNames = disabledToolNames,
            hardAllowedToolNames = hardAllowedToolNames,
        ),
    )

    /** AUTO always discovers the assistant's complete enabled set before applying its intent profile. */
    private suspend fun discoverAutoCandidates(ctx: ToolResolveContext): List<DescribedTool> =
        discoverWithDescriptors(ctx.copy(mode = AgentMode.AGENT))

    private fun profileRequest(
        ctx: ToolResolveContext,
        intent: AgentIntent,
        inputTrust: InputTrust,
        defaultToolTimeoutMillis: Long,
        disabledToolNames: Set<String>,
        hardAllowedToolNames: Set<String>?,
    ) = ToolProfileRequest(
        intent = intent,
        inputTrust = inputTrust,
        assistantId = ctx.assistant.id.toString(),
        workspaceId = ctx.assistant.workspaceId?.toString(),
        permissionPolicy = ctx.permissionPolicy,
        defaultToolTimeoutMillis = defaultToolTimeoutMillis,
        disabledToolNames = disabledToolNames,
        hardAllowedToolNames = hardAllowedToolNames,
        isSubagentRun = ctx.isSubagentRun,
    )

    /** 仅用于测试：返回当前注册的 provider order 列表 */
    fun providerOrders(): List<Int> = providers.map { it.order }.sorted()
}
