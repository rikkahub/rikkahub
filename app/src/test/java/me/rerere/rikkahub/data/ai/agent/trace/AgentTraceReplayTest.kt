package me.rerere.rikkahub.data.ai.agent.trace

import me.rerere.rikkahub.data.db.entity.AgentTraceEvent
import me.rerere.rikkahub.data.model.AgentTraceAttributes
import me.rerere.rikkahub.data.model.AgentTraceErrorCategory
import me.rerere.rikkahub.data.model.AgentTraceEventType
import me.rerere.rikkahub.data.model.AgentTraceRedactor
import me.rerere.rikkahub.data.model.AgentTraceStatus
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceReplayTest {
    @Test
    fun `redactor accepts hashes and never retains source identifiers`() {
        val secret = "Authorization: Bearer top-secret /private/path"
        val hash = AgentTraceRedactor.hash(secret)!!
        val attributes = AgentTraceAttributes(toolNameHash = hash).validated()

        assertEquals(64, hash.length)
        assertFalse(JsonInstant.encodeToString(attributes).contains(secret))
        assertTrue(runCatching { AgentTraceAttributes(toolNameHash = secret).validated() }.isFailure)
    }

    @Test
    fun `redactor rejects unknown trace attributes instead of silently dropping them`() {
        val encoded = AgentTraceRedactor.encodeAttributes(AgentTraceAttributes(toolNameHash = AgentTraceRedactor.hash("tool")))

        assertEquals(encoded, AgentTraceRedactor.encodeAttributes(AgentTraceRedactor.decodeAttributes(encoded)))
        assertTrue(runCatching {
            AgentTraceRedactor.decodeAttributes("{\"schemaVersion\":1,\"unexpected\":\"value\"}")
        }.isFailure)
    }

    @Test
    fun `replay validates policy and budget terminal states without executing side effects`() {
        val replay = DeterministicAgentTraceReplay()
        val events = listOf(
            event(0, AgentTraceEventType.RUN_STARTED, AgentTraceStatus.STARTED),
            event(1, AgentTraceEventType.PREFLIGHT, AgentTraceStatus.STARTED),
            event(2, AgentTraceEventType.CONTEXT_PLANNED, AgentTraceStatus.ALLOWED),
            event(3, AgentTraceEventType.MODEL_CALL_STARTED, AgentTraceStatus.STARTED),
            event(4, AgentTraceEventType.MODEL_CALL_FINISHED, AgentTraceStatus.SUCCEEDED),
            event(5, AgentTraceEventType.POLICY_DECISION, AgentTraceStatus.ALLOWED),
            event(6, AgentTraceEventType.TOOL_STARTED, AgentTraceStatus.STARTED),
            event(7, AgentTraceEventType.TOOL_FINISHED, AgentTraceStatus.SUCCEEDED),
            event(8, AgentTraceEventType.RUN_FINISHED, AgentTraceStatus.SUCCEEDED),
        )

        assertEquals(ReplayResult(AgentTraceStatus.SUCCEEDED.name, 1, 1), replay.replay(events))
        val blocked = events.take(2) + listOf(
            event(2, AgentTraceEventType.CONTEXT_PLANNED, AgentTraceStatus.BLOCKED),
            event(3, AgentTraceEventType.RUN_FINISHED, AgentTraceStatus.BLOCKED),
        )
        assertEquals(AgentTraceStatus.BLOCKED.name, replay.replay(blocked).terminalStatus)
        val deniedTool = events.map { event ->
            if (event.sequence == 5 || event.sequence == 7) {
                event.copy(status = AgentTraceStatus.DENIED.name)
            } else {
                event
            }
        }
        val deniedWithoutStart = deniedTool.filterNot { it.type == AgentTraceEventType.TOOL_STARTED.name }
        assertEquals(0, replay.replay(deniedWithoutStart).toolCalls)
    }

    @Test
    fun `replay pairs parallel tools by execution identity and rejects an unknown finish`() {
        val replay = DeterministicAgentTraceReplay()
        val parallel = listOf(
            event(0, AgentTraceEventType.RUN_STARTED, AgentTraceStatus.STARTED),
            event(1, AgentTraceEventType.PREFLIGHT, AgentTraceStatus.STARTED),
            event(2, AgentTraceEventType.CONTEXT_PLANNED, AgentTraceStatus.ALLOWED),
            event(3, AgentTraceEventType.MODEL_CALL_STARTED, AgentTraceStatus.STARTED),
            event(4, AgentTraceEventType.MODEL_CALL_FINISHED, AgentTraceStatus.SUCCEEDED),
            event(5, AgentTraceEventType.TOOL_STARTED, AgentTraceStatus.STARTED, "explore-a"),
            event(6, AgentTraceEventType.TOOL_STARTED, AgentTraceStatus.STARTED, "explore-b"),
            event(7, AgentTraceEventType.TOOL_FINISHED, AgentTraceStatus.SUCCEEDED, "explore-b"),
            event(8, AgentTraceEventType.TOOL_FINISHED, AgentTraceStatus.SUCCEEDED, "explore-a"),
            event(9, AgentTraceEventType.RUN_FINISHED, AgentTraceStatus.SUCCEEDED),
        )

        assertEquals(2, replay.replay(parallel).toolCalls)
        val unknownFinish = parallel.map { event ->
            if (event.sequence == 8) event.copy(attributesJson = attributes("missing")) else event
        }
        assertTrue(runCatching { replay.replay(unknownFinish) }.isFailure)
    }

    @Test
    fun `replay resumes from a retained truncation checkpoint`() {
        val events = listOf(
            event(0, AgentTraceEventType.RUN_STARTED, AgentTraceStatus.STARTED),
            event(5, AgentTraceEventType.TRACE_TRUNCATED, AgentTraceStatus.TRUNCATED),
            event(6, AgentTraceEventType.MODEL_CALL_STARTED, AgentTraceStatus.STARTED),
            event(7, AgentTraceEventType.MODEL_CALL_FINISHED, AgentTraceStatus.SUCCEEDED),
            event(8, AgentTraceEventType.TOOL_STARTED, AgentTraceStatus.STARTED, "retained-tool"),
            event(9, AgentTraceEventType.TOOL_FINISHED, AgentTraceStatus.SUCCEEDED, "retained-tool"),
            event(10, AgentTraceEventType.RUN_FINISHED, AgentTraceStatus.SUCCEEDED),
        )

        assertEquals(ReplayResult(AgentTraceStatus.SUCCEEDED.name, 1, 1), DeterministicAgentTraceReplay().replay(events))
    }

    private fun event(
        sequence: Int,
        type: AgentTraceEventType,
        status: AgentTraceStatus,
        executionIdentity: String = "execution",
    ) = AgentTraceEvent(
        id = "event-$sequence",
        runId = "run",
        sequence = sequence,
        type = type.name,
        status = status.name,
        timestampMillis = sequence.toLong(),
        errorCategory = AgentTraceErrorCategory.NONE.name,
        attributesJson = if (type in setOf(AgentTraceEventType.TOOL_STARTED, AgentTraceEventType.TOOL_FINISHED)) {
            attributes(executionIdentity)
        } else {
            JsonInstant.encodeToString(AgentTraceAttributes())
        },
        createdAt = sequence.toLong(),
    )

    private fun attributes(executionIdentity: String): String = JsonInstant.encodeToString(
        AgentTraceAttributes(toolExecutionIdHash = AgentTraceRedactor.hash(executionIdentity)),
    )
}
