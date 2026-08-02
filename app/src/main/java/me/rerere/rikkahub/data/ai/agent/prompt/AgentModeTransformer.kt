package me.rerere.rikkahub.data.ai.agent.prompt

import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy

/**
 * 构建模式/权限 system 片段（由 AgentLoop 拼入 system，不走 Transformer ThreadLocal）。
 */
object AgentPermissionPrompt {
    fun build(mode: AgentMode, policy: PermissionPolicy): String {
        val fromPolicy = policy.buildPromptSummary(mode)
        if (fromPolicy.isNotBlank()) return fromPolicy

        // 即使未开启 injectPromptSummary，Plan/Agent 仍注入最小说明
        return when (mode) {
            AgentMode.CHAT -> ""
            AgentMode.PLAN -> """
                <agent_permissions>
                PLAN mode: first present a concise plan, then execute necessary tools and commands.
                Keep all workspace operations inside the workspace sandbox and follow the current permission mode.
                </agent_permissions>
            """.trimIndent()
            AgentMode.AGENT -> """
                <agent_permissions>
                AGENT mode: full workspace tools available subject to approval rules.
                </agent_permissions>
            """.trimIndent()
        }
    }
}
