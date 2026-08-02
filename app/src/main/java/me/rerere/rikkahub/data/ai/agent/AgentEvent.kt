package me.rerere.rikkahub.data.ai.agent

/**
 * 可选可观测事件（Phase 5）。当前主要用于日志 / 扩展，不影响生成语义。
 */
sealed interface AgentEvent {
    data class StepStarted(val stepIndex: Int) : AgentEvent
    data class GenerationStarted(val stepIndex: Int) : AgentEvent
    data class ToolApprovalPending(val toolNames: List<String>) : AgentEvent
    data class ToolExecutionStarted(val toolName: String, val toolCallId: String) : AgentEvent
    data class ToolExecutionFinished(val toolName: String, val toolCallId: String, val success: Boolean) : AgentEvent
    data class LoopFinished(val reason: String) : AgentEvent
}
