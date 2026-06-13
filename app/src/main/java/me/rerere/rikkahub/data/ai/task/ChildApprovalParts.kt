package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.model.Conversation

/**
 * Pure transcript edits for the child-approval round-trip (SPEC.md M4 / Gap A, invariant
 * TASK_APPROVAL_VISIBLE): a forwarded child approval becomes a parent-visible pending
 * [UIMessagePart.Tool] anchored INSIDE the message that carries the parent's running `task` step,
 * and the parent's decision is written back onto that same part. Kept as top-level pure functions
 * over [Conversation] so the JVM suite drives them without `ChatService`.
 */

/**
 * Anchor a pending child approval under the parent's `task` step: append a pending
 * [UIMessagePart.Tool] (id = the namespaced `taskId/childToolCallId`, input = the child call's
 * arguments) to the LAST assistant message that contains an unexecuted `task` tool part — that
 * message is the one whose spawn is currently suspended waiting for this very approval.
 *
 * Returns null when no such anchor exists (no visible running task step) — the caller must then
 * fail CLOSED (deny) rather than suspend on an approval the user can never see. Idempotent: if
 * the part is already present the conversation is returned unchanged.
 */
fun injectChildApprovalPart(
    conversation: Conversation,
    namespacedToolCallId: String,
    toolName: String,
    argumentsJson: String,
): Conversation? {
    val nodes = conversation.messageNodes
    val nodeIndex = nodes.indexOfLast { node ->
        val message = node.messages.getOrNull(node.selectIndex) ?: return@indexOfLast false
        message.role == MessageRole.ASSISTANT && message.parts.any {
            it is UIMessagePart.Tool && it.toolName == SPAWN_TOOL_NAME && !it.isExecuted
        }
    }
    if (nodeIndex < 0) return null

    val node = nodes[nodeIndex]
    val message = node.messages[node.selectIndex]
    if (message.parts.any { it is UIMessagePart.Tool && it.toolCallId == namespacedToolCallId }) {
        return conversation
    }

    val pendingPart = UIMessagePart.Tool(
        toolCallId = namespacedToolCallId,
        toolName = toolName,
        input = argumentsJson,
        approvalState = ToolApprovalState.Pending,
    )
    val updatedNode = node.copy(
        messages = node.messages.mapIndexed { index, m ->
            if (index == node.selectIndex) m.copy(parts = m.parts + pendingPart) else m
        }
    )
    return conversation.copy(
        messageNodes = nodes.mapIndexed { index, n -> if (index == nodeIndex) updatedNode else n }
    )
}

/**
 * Write an approval decision onto every [UIMessagePart.Tool] with [toolCallId] — the same
 * node-wide mapping `handleToolApproval` applies to parent tools. For a CHILD part the decision
 * also becomes the part's output, so the resolved record is self-contained (`isExecuted`,
 * renderable, and harmless to any later transcript pass) even though the real tool result lives
 * in the child's transcript, never the parent's.
 */
fun resolveChildApprovalPart(
    conversation: Conversation,
    toolCallId: String,
    state: ToolApprovalState,
): Conversation = conversation.copy(
    messageNodes = conversation.messageNodes.map { node ->
        node.copy(
            messages = node.messages.map { message ->
                message.copy(
                    parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                            part.copy(
                                approvalState = state,
                                output = part.output.ifEmpty {
                                    listOf(UIMessagePart.Text(childApprovalOutcomeJson(state)))
                                },
                            )
                        } else {
                            part
                        }
                    }
                )
            }
        )
    }
)

private fun childApprovalOutcomeJson(state: ToolApprovalState): String = when (state) {
    ToolApprovalState.Approved -> """{"status":"approved"}"""
    is ToolApprovalState.Answered -> """{"status":"approved"}"""
    is ToolApprovalState.Denied -> """{"status":"denied"}"""
    ToolApprovalState.Auto, ToolApprovalState.Pending -> """{"status":"pending"}"""
}
