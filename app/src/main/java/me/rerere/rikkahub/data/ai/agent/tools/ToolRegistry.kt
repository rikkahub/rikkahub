package me.rerere.rikkahub.data.ai.agent.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.isPlanModeBlockedTool
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool

class ToolRegistry(
    private val providers: List<ToolProvider>,
) {
    /**
     * 按 [ToolProvider.order] 解析并合并工具列表。
     * Plan 模式下仅保留 [me.rerere.rikkahub.data.ai.agent.PlanModeAllowedTools]。
     */
    suspend fun resolve(ctx: ToolResolveContext): List<Tool> {
        return resolveWithDescriptors(ctx).map(DescribedTool::tool)
    }

    /** Policy-aware counterpart to [resolve], retaining descriptors for each resolved tool. */
    suspend fun resolveWithDescriptors(ctx: ToolResolveContext): List<DescribedTool> {
        val tools = providers
            .sortedBy { it.order }
            .filter { it.isEnabled(ctx) }
            .flatMap { it.provideWithDescriptors(ctx) }

        return when (ctx.mode) {
            AgentMode.PLAN -> tools.filterNot { isPlanModeBlockedTool(it.tool.name) }
            AgentMode.CHAT, AgentMode.AGENT -> tools
        }
    }

    /** 仅用于测试：返回当前注册的 provider order 列表 */
    fun providerOrders(): List<Int> = providers.map { it.order }.sorted()
}
