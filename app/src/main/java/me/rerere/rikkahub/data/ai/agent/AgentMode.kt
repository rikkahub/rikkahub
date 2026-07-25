package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agent 运行模式（默认 [CHAT] = 今日 RikkaHub 全功能行为）。
 *
 * - [CHAT]：普通对话 / 工具循环，行为与改造前一致
 * - [PLAN]：计划模式（学 Claude Code）：只读探索，禁用 Workspace 写/shell
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

/** Plan 模式下禁止暴露给模型的 Workspace 工具（硬拒绝策略：不注册）。 */
val PlanModeBlockedTools: Set<String> = setOf(
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
)
