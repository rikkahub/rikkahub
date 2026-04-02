package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage

internal fun UIMessage.hasBlockingToolsForContinuation(): Boolean {
    return getTools().any { tool ->
        !tool.isExecuted && when (tool.approvalState) {
            is ToolApprovalState.Approved,
            is ToolApprovalState.Answered,
            is ToolApprovalState.Denied,
            -> false

            else -> true
        }
    }
}
