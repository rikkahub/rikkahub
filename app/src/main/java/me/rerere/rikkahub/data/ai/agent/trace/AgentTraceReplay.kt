package me.rerere.rikkahub.data.ai.agent.trace

import me.rerere.rikkahub.data.db.entity.AgentTraceEvent
import me.rerere.rikkahub.data.model.AgentTraceEventType
import me.rerere.rikkahub.data.model.AgentTraceStatus

/**
 * Pure replay validator. It consumes only already-redacted events and never invokes a provider,
 * a tool implementation, filesystem, network, or approval UI.
 */
class DeterministicAgentTraceReplay {
    fun replay(events: List<AgentTraceEvent>): ReplayResult {
        // Observations belong to one replay only. Keeping them on the replay instance leaks counts
        // into subsequent validations and makes the otherwise pure result depend on call history.
        val provider = FakeReplayProvider()
        val tool = FakeReplayTool()
        require(events.zipWithNext().all { (before, after) -> after.sequence > before.sequence }) { "TRACE_SEQUENCE_NOT_MONOTONIC" }
        require(events.firstOrNull()?.type == AgentTraceEventType.RUN_STARTED.name) { "TRACE_MISSING_RUN_START" }
        var modelOpen = false
        val openTools = mutableMapOf<String, AgentTraceEvent>()
        val finishedTools = mutableSetOf<String>()
        var terminal: String? = null
        var policyDenied = false
        var contextBlocked = false
        var preflightComplete = false
        var modelCompleted = false
        events.forEach { event ->
            require(terminal == null) { "TRACE_EVENT_AFTER_TERMINAL" }
            when (AgentTraceEventType.valueOf(event.type)) {
                AgentTraceEventType.PREFLIGHT -> preflightComplete = true
                AgentTraceEventType.CONTEXT_PLANNED -> {
                    require(preflightComplete) { "TRACE_CONTEXT_BEFORE_PREFLIGHT" }
                    contextBlocked = event.status == AgentTraceStatus.BLOCKED.name
                }
                AgentTraceEventType.MODEL_CALL_STARTED -> {
                    require(preflightComplete && !contextBlocked && !modelOpen) { "TRACE_MODEL_NOT_READY" }
                    modelOpen = true
                    provider.observe(event)
                }
                AgentTraceEventType.MODEL_CALL_FINISHED -> {
                    require(modelOpen) { "TRACE_MODEL_NOT_OPEN" }
                    modelOpen = false
                    modelCompleted = true
                    provider.observe(event)
                }
                AgentTraceEventType.POLICY_DECISION -> policyDenied = event.status == AgentTraceStatus.DENIED.name
                AgentTraceEventType.TOOL_STARTED -> {
                    val identity = event.toolExecutionIdentity()
                    require(modelCompleted && !policyDenied && identity !in openTools && identity !in finishedTools) {
                        "TRACE_TOOL_NOT_ALLOWED"
                    }
                    openTools[identity] = event
                    tool.observe(event)
                }
                AgentTraceEventType.TOOL_FINISHED -> {
                    val identity = event.toolExecutionIdentity()
                    if (event.status == AgentTraceStatus.DENIED.name) {
                        // Rejected tools never execute, so they intentionally have no TOOL_STARTED.
                        require(modelCompleted && identity !in openTools && finishedTools.add(identity)) { "TRACE_DENIED_TOOL_INVALID" }
                    } else {
                        require(openTools.remove(identity) != null && finishedTools.add(identity)) { "TRACE_TOOL_NOT_OPEN" }
                        tool.observe(event)
                    }
                }
                AgentTraceEventType.TRACE_TRUNCATED -> {
                    require(event.status == AgentTraceStatus.TRUNCATED.name) { "TRACE_INVALID_TRUNCATION" }
                    // Retention writes this only at a completed-step boundary.
                    modelOpen = false
                    modelCompleted = true
                    policyDenied = false
                    preflightComplete = true
                    contextBlocked = false
                    require(openTools.isEmpty()) { "TRACE_TRUNCATED_WITH_OPEN_TOOL" }
                }
                AgentTraceEventType.RUN_FINISHED -> {
                    require(event.status in TERMINAL_STATUSES) { "TRACE_INVALID_TERMINAL" }
                    require(preflightComplete || event.status == AgentTraceStatus.CANCELLED.name) {
                        "TRACE_TERMINAL_BEFORE_PREFLIGHT"
                    }
                    require(!contextBlocked || event.status == AgentTraceStatus.BLOCKED.name) { "TRACE_BUDGET_TERMINAL_MISMATCH" }
                    terminal = event.status
                }
                else -> Unit
            }
        }
        require(!modelOpen && openTools.isEmpty()) { "TRACE_OPEN_OPERATION" }
        return ReplayResult(checkNotNull(terminal) { "TRACE_MISSING_TERMINAL" }, provider.events.size / 2, tool.events.size / 2)
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            AgentTraceStatus.SUCCEEDED.name,
            AgentTraceStatus.FAILED.name,
            AgentTraceStatus.BLOCKED.name,
            AgentTraceStatus.CANCELLED.name,
            AgentTraceStatus.INTERRUPTED.name,
        )
    }
}

private fun AgentTraceEvent.toolExecutionIdentity(): String {
    return me.rerere.rikkahub.data.model.AgentTraceRedactor.decodeAttributes(attributesJson).toolExecutionIdHash
        ?: throw IllegalArgumentException("TRACE_TOOL_IDENTITY_MISSING")
}

data class ReplayResult(val terminalStatus: String, val modelCalls: Int, val toolCalls: Int)

class FakeReplayProvider {
    internal val events = mutableListOf<AgentTraceEvent>()
    internal fun observe(event: AgentTraceEvent) { events += event }
}

class FakeReplayTool {
    internal val events = mutableListOf<AgentTraceEvent>()
    internal fun observe(event: AgentTraceEvent) { events += event }
}
