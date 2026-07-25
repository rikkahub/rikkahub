package me.rerere.rikkahub.data.ai.agent.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.PlanModeBlockedTools

class ToolRegistry(
    private val providers: List<ToolProvider>,
) {
    /**
     * 按 [ToolProvider.order] 解析并合并工具列表。
     * Plan 模式下过滤 [PlanModeBlockedTools]。
     */
    suspend fun resolve(ctx: ToolResolveContext): List<Tool> {
        val tools = providers
            .sortedBy { it.order }
            .filter { it.isEnabled(ctx) }
            .flatMap { it.provide(ctx) }

        return when (ctx.mode) {
            AgentMode.PLAN -> tools.filterNot { it.name in PlanModeBlockedTools }
            AgentMode.CHAT, AgentMode.AGENT -> tools
        }
    }

    /** 仅用于测试：返回当前注册的 provider order 列表 */
    fun providerOrders(): List<Int> = providers.map { it.order }.sorted()
}
