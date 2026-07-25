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
                PLAN mode: read-only exploration. Do not write files or run shell.
                Deliver a concrete plan; user may switch to AGENT to execute.
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
