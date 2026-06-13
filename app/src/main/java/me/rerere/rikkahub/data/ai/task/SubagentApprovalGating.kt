package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.TaskApprovalGate
import me.rerere.ai.runtime.task.TaskApprovalDecision
import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * Rewrites a spawned subagent's tool pool so its approval-gated tools round-trip through the
 * parent's approval surface instead of being stripped (SPEC.md M4 / Gap A, maintainer decision
 * #2). For every `needsApproval=true` tool:
 *
 *  - `needsApproval` flips to FALSE in the child pool — the child's runtime must never gate the
 *    tool itself (its approval UI is unreachable mid-subagent; that unreachability is exactly why
 *    v1 stripped these tools). The GATE is now the only decision point.
 *  - `execute` is wrapped: it first asks [gate] (the [TaskApprovalRouter] in production), which
 *    auto-denies anything not on the agent type's explicit allowlist and forwards the rest to the
 *    parent as a visible pending approval. Approval runs the REAL tool with the original
 *    arguments; denial returns [deniedChildToolResult] as the tool result WITHOUT executing
 *    anything — both outcomes resume the child (decision #2).
 *
 * `needsApproval=false` tools pass through as the same instance — the pool rewrite touches only
 * what it must.
 */
fun gateSubagentTools(
    tools: List<Tool>,
    taskId: Uuid,
    gate: TaskApprovalGate,
): List<Tool> = tools.map { tool ->
    if (!tool.needsApproval) {
        tool
    } else {
        tool.copy(
            needsApproval = false,
            execute = { args ->
                // The engine-wide Tool.execute signature carries no call id, so the gate request
                // mints one; uniqueness is all the namespacing needs.
                val request = TaskApprovalRequest(
                    childToolCallId = Uuid.random().toString(),
                    toolName = tool.name,
                    argumentsJson = args.toString(),
                )
                when (val decision = gate.await(taskId, request)) {
                    TaskApprovalDecision.Approved -> tool.execute(args)
                    // The answer IS the tool result — the real execute never runs, mirroring the
                    // parent runtime's own Answered handling (ask_user-class tools whose execute
                    // deliberately throws because the HITL answer replaces it).
                    is TaskApprovalDecision.Answered -> listOf(UIMessagePart.Text(decision.answer))
                    is TaskApprovalDecision.Denied ->
                        listOf(UIMessagePart.Text(deniedChildToolResult(tool.name, decision.reason)))
                }
            },
        )
    }
}

/**
 * The tool result a denied child tool call resumes with. Model-facing (not a UI resource): it
 * must tell the child unambiguously that the call did not run and not to retry it.
 */
fun deniedChildToolResult(toolName: String, reason: String = ""): String =
    "Tool call \"$toolName\" was denied and did not run" +
        (if (reason.isBlank()) "" else " (reason: $reason)") +
        ". Do not retry it; continue without it or state what could not be done."
