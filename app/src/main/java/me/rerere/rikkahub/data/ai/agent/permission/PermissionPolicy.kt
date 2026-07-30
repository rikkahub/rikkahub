package me.rerere.rikkahub.data.ai.agent.permission

import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.isPlanModeBlockedTool

enum class ApprovalAction {
    /** 自动执行（仍尊重 Tool.needsApproval） */
    AUTO,

    /** 强制进入用户审批 */
    ASK,
}

/**
 * 权限策略。
 *
 * 默认 [compatibleDefault] 对所有类别为 [ApprovalAction.AUTO]，
 * 实际是否审批完全由各 [Tool.needsApproval] 决定（= 改造前行为）。
 */
data class PermissionPolicy(
    val byCategory: Map<ToolCategory, ApprovalAction> = emptyMap(),
    val injectPromptSummary: Boolean = false,
) {
    fun actionFor(category: ToolCategory): ApprovalAction =
        byCategory[category] ?: ApprovalAction.AUTO

    fun actionForTool(toolName: String): ApprovalAction =
        actionFor(ToolCategory.ofToolName(toolName))

    /**
     * 是否需要用户审批。
     * - Plan 模式拦截不在固定只读名单中的工具（若仍被调用）
     * - 策略 ASK 强制审批
     * - 否则回落到 tool.needsApproval
     */
    fun requiresApproval(tool: Tool, args: JsonElement, mode: AgentMode): Boolean {
        if (mode == AgentMode.PLAN && isPlanModeBlockedTool(tool.name)) {
            return true
        }
        if (actionForTool(tool.name) == ApprovalAction.ASK) {
            return true
        }
        return tool.needsApproval(args)
    }

    fun buildPromptSummary(mode: AgentMode): String {
        if (!injectPromptSummary) return ""
        return buildString {
            appendLine("<agent_permissions>")
            appendLine("Runtime mode: ${mode.name}.")
            when (mode) {
                AgentMode.PLAN -> {
                    appendLine("PLAN mode is active: you may explore with read-only tools.")
                    appendLine("Only explicitly registered read-only tools are available; all other tools are blocked.")
                    appendLine("Produce a clear plan for the user; they can switch to AGENT mode to execute.")
                }
                AgentMode.AGENT -> {
                    appendLine("AGENT mode: full tool access subject to per-tool approval rules.")
                    appendLine("Prefer workspace_edit_file for targeted edits; use workspace_shell for shell tasks.")
                }
                AgentMode.CHAT -> {
                    appendLine("CHAT mode: standard assistant tools are available per configuration.")
                }
            }
            appendLine("MCP tools (names starting with mcp__) are not covered by the workspace proot sandbox;")
            appendLine("they enforce their own guardrails.")
            append("</agent_permissions>")
        }.trim()
    }

    companion object {
        /** 默认兼容策略：行为 = 改造前 */
        fun compatibleDefault(injectPromptForWorkspace: Boolean = false): PermissionPolicy =
            PermissionPolicy(
                byCategory = emptyMap(),
                injectPromptSummary = injectPromptForWorkspace,
            )
    }
}
