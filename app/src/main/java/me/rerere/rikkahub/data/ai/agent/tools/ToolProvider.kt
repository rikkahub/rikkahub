package me.rerere.rikkahub.data.ai.agent.tools

import me.rerere.ai.core.Tool

/**
 * 工具来源扩展点。各 Provider 对应原 ChatService 工具组装中的一段逻辑。
 * [order] 保证注册顺序契约（见 agent-runtime-design.md）。
 */
interface ToolProvider {
    val order: Int

    fun isEnabled(ctx: ToolResolveContext): Boolean

    suspend fun provide(ctx: ToolResolveContext): List<Tool>
}

/**
 * 注册顺序。
 * Memory 在旧 GenerationHandler 中先于 ChatService 传入的 tools 合并，故 order 最小。
 * 其余顺序与 chat-generation-pipeline 文档一致：搜索 → 本地 → 对话 → Workspace → Skill → MCP。
 */
object ToolProviderOrder {
    const val MEMORY = 5
    const val SEARCH = 10
    const val LOCAL = 20
    const val CONVERSATION = 30
    const val WORKSPACE = 40
    /** Explore subagent 入口（仅父会话） */
    const val SUBAGENT = 45
    const val SKILL = 50
    const val MCP = 60
}
