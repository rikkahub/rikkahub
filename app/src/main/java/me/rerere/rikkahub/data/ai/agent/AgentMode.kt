package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agent 运行模式（默认 [CHAT] = 今日 RikkaHub 全功能行为）。
 *
 * - [CHAT]：普通对话 / 工具循环，行为与改造前一致
 * - [PLAN]：计划模式（学 Claude Code）：只读探索，仅注册明确允许的无副作用工具
 * - [AGENT]：执行模式：全工具可用，可在 Plan 确认后切换
 */
@Serializable
enum class AgentMode {
    @SerialName("chat")
    CHAT,

    @SerialName("plan")
    PLAN,

    @SerialName("agent")
    AGENT;

    fun next(): AgentMode = when (this) {
        CHAT -> PLAN
        PLAN -> AGENT
        AGENT -> CHAT
    }

    companion object {
        fun fromStorage(value: String?): AgentMode {
            if (value.isNullOrBlank()) return CHAT
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                    it.name.lowercase() == value.lowercase()
            } ?: CHAT
        }
    }
}

/**
 * PLAN 的固定只读工具名单。未知工具一律拒绝，避免新增内置工具或 MCP 工具意外获得权限。
 * Calendar 查询会继续走工具自身的用户审批；ScreenTime 在缺权限时会打开系统设置，因此不在名单内。
 */
val PlanModeAllowedTools: Set<String> = setOf(
    "workspace_read_file",
    "artifact_read",
    "artifact_search",
    "search_web",
    "scrape_web",
    "get_time_info",
    "calendar_query",
    "recent_chats",
    "conversation_search",
    "use_skill",
)

/** PLAN 中不在只读名单内的工具永不注册、也永不执行。 */
fun isPlanModeBlockedTool(toolName: String): Boolean = toolName !in PlanModeAllowedTools
