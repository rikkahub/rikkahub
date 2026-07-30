package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.AgentRoutingSnapshot
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.AgentStepEntity
import me.rerere.rikkahub.data.db.entity.ToolExecutionEntity
import me.rerere.rikkahub.data.db.entity.AgentTraceEvent
import me.rerere.rikkahub.data.model.AgentApprovalStatus
import me.rerere.rikkahub.data.model.AgentApprovalSummary
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunError
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentRunSummary
import me.rerere.rikkahub.data.model.ChildRunReport
import me.rerere.rikkahub.data.model.AgentStepStatus
import me.rerere.rikkahub.data.model.ToolExecutionStatus
import me.rerere.rikkahub.data.model.ToolExecutionSummary
import me.rerere.rikkahub.data.model.AgentTraceAttributes
import me.rerere.rikkahub.data.model.AgentTraceErrorCategory
import me.rerere.rikkahub.data.model.AgentTraceEventType
import me.rerere.rikkahub.data.model.AgentTraceStatus
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunPresentationTest {
    @Test
    fun waitingRunUsesStructuredConfigurationAndApprovalReason() {
        val detail = AgentRunDetail(
            run = run(
                status = AgentRunStatus.WAITING_APPROVAL.name,
                config = AgentRunConfigSnapshot(modelId = "gpt-test", agentMode = "AGENT", maxSteps = 12),
                summary = AgentRunSummary(completedSteps = 3, outcome = "do not render this"),
            ),
            steps = listOf(
                AgentStepEntity("step", "run", 3, "tool", AgentStepStatus.RUNNING.name, createdAt = 1_000, updatedAt = 2_500),
            ),
            tools = listOf(
                ToolExecutionEntity(
                    "tool", "run", "step", 0, "workspace.read", ToolExecutionStatus.WAITING_APPROVAL.name,
                    summaryJson = JsonInstant.encodeToString(
                        ToolExecutionSummary(category = "workspace", operation = "read", outputBytes = 256)
                    ),
                    createdAt = 1_000, updatedAt = 2_500,
                ),
            ),
            approvals = listOf(
                AgentApprovalEntity(
                    "approval", "run", "tool", 0, AgentApprovalStatus.PENDING.name,
                    JsonInstant.encodeToString(AgentApprovalSummary(reasonCode = "workspace_write")), 2_000,
                ),
            ),
        )

        val presentation = detail.toPresentation()

        assertEquals("等待审批", presentation.status)
        assertEquals("gpt-test", presentation.model)
        assertEquals(AgentRunRoutingKind.LEGACY, presentation.routing.kind)
        assertEquals("AGENT", presentation.routing.legacyMode)
        assertEquals(12, presentation.maxSteps)
        assertEquals(3, presentation.completedSteps)
        assertEquals("步骤 4 · tool", presentation.currentStep)
        assertEquals("此操作需要你的批准", presentation.waitingReason)
        assertEquals("workspace / read", presentation.timeline.single { it.label.startsWith("工具") }.summary)
        assertEquals("输出已脱敏（256 B）", presentation.timeline.single { it.label.startsWith("工具") }.outputSummary)
        assertFalse(presentation.toString().contains("do not render this"))
    }

    @Test
    fun autoSnapshotsExposeFrozenRoutingAuditFacts() {
        AgentIntent.entries.forEach { intent ->
            val presentation = run(
                status = AgentRunStatus.RUNNING.name,
                config = autoConfig(
                    intent = intent,
                    reasonCode = if (intent == AgentIntent.EXECUTE) "explicit_mutation" else "general_answer",
                    toolNames = listOf("workspace_read", "ask_user"),
                ),
            ).toPresentation()

            assertEquals(AgentRunRoutingKind.AUTO, presentation.routing.kind)
            assertEquals(intent, presentation.routing.intent)
            assertEquals(InputTrust.USER_DIRECT, presentation.routing.inputTrust)
            assertEquals(2, presentation.routing.toolCount)
            assertEquals(listOf("ask_user", "workspace_read"), presentation.routing.visibleToolNames)
            assertEquals("policy:v1", presentation.routing.permissionDigest)
            assertEquals(AgentRoutingSnapshot.CURRENT_VERSION, presentation.routing.policyVersion)
        }
    }

    @Test
    fun unknownRoutingReasonNeverLeaksAndLongToolListsAreTruncated() {
        val unknownReason = "internal_future_reason_42"
        val presentation = run(
            status = AgentRunStatus.RUNNING.name,
            config = autoConfig(
                intent = AgentIntent.EXPLORE,
                reasonCode = unknownReason,
                toolNames = (0..11).map { "tool_${it.toString().padStart(2, '0')}" },
            ),
        ).toPresentation()

        assertNull(presentation.routing.reasonCode)
        assertFalse(presentation.toString().contains(unknownReason))
        assertEquals(12, presentation.routing.toolCount)
        assertEquals(8, presentation.routing.visibleToolNames.size)
        assertTrue(presentation.routing.toolNamesTruncated)
    }

    @Test
    fun malformedSnapshotsUseGenericUnavailablePresentation() {
        val presentation = run(AgentRunStatus.BLOCKED.name).copy(
            configSnapshotJson = "{not-json",
        ).toPresentation()

        assertEquals(AgentRunRoutingKind.UNAVAILABLE, presentation.routing.kind)
        assertEquals(AgentRunRoutingDegradedReason.MALFORMED, presentation.routing.degradedReason)
        assertFalse(presentation.toString().contains("not-json"))
    }

    @Test
    fun activePresentationRejectsDetailFromReplacedRun() {
        val activeRun = run(AgentRunStatus.RUNNING.name).copy(id = "run-b")
        val staleDetail = AgentRunDetail(
            run = run(AgentRunStatus.RUNNING.name).copy(id = "run-a"),
            steps = listOf(
                AgentStepEntity(
                    "old-step",
                    "run-a",
                    8,
                    "stale",
                    AgentStepStatus.RUNNING.name,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            ),
            tools = emptyList(),
            approvals = emptyList(),
        )

        val presentation = selectActiveRunPresentation(activeRun, staleDetail)

        assertEquals("run-b", presentation?.runId)
        assertNull(presentation?.currentStep)
    }

    @Test
    fun presentationMapsInternalFailureAndApprovalCodesToSafeText() {
        val presentation = AgentRunDetail(
            run = run(status = AgentRunStatus.FAILED.name).copy(
                errorJson = JsonInstant.encodeToString(AgentRunError("INTERNAL_SECRET_CODE", "runtime")),
            ),
            steps = emptyList(),
            tools = emptyList(),
            approvals = emptyList(),
        ).toPresentation()

        assertEquals("运行时发生错误", presentation.failureCategory)
        assertFalse(presentation.toString().contains("INTERNAL_SECRET_CODE"))
    }

    @Test
    fun interruptedRunExplainsThatItMustBeRestartedFromChat() {
        val presentation = AgentRunDetail(
            run = run(status = AgentRunStatus.INTERRUPTED.name),
            steps = emptyList(),
            tools = emptyList(),
            approvals = emptyList(),
        ).toPresentation()

        assertTrue(presentation.statusDescription.orEmpty().contains("不自动恢复"))
        assertEquals("1.50s", durationLabel(0, 1_500))
        assertEquals("1分1秒", durationLabel(0, 61_000))
    }

    @Test
    fun timelineKeepsToolsDirectlyAfterTheirStepAndSortsOnlyOrphans() {
        val timeline = AgentRunDetail(
            run = run(AgentRunStatus.RUNNING.name),
            steps = listOf(
                AgentStepEntity("first", "run", 0, "agent", AgentStepStatus.SUCCEEDED.name, createdAt = 0, updatedAt = 0),
                AgentStepEntity("second", "run", 1, "agent", AgentStepStatus.RUNNING.name, createdAt = 0, updatedAt = 0),
            ),
            tools = listOf(
                ToolExecutionEntity("tool-second", "run", "second", 0, "second-tool", ToolExecutionStatus.PENDING.name, createdAt = 0, updatedAt = 0),
                ToolExecutionEntity("tool-first", "run", "first", 1, "first-tool", ToolExecutionStatus.PENDING.name, createdAt = 0, updatedAt = 0),
                ToolExecutionEntity("orphan-first", "run", "missing", 2, "orphan-first-tool", ToolExecutionStatus.PENDING.name, createdAt = 0, updatedAt = 0),
                ToolExecutionEntity("orphan-second", "run", "missing", 3, "orphan-second-tool", ToolExecutionStatus.PENDING.name, createdAt = 0, updatedAt = 0),
            ),
            approvals = emptyList(),
        ).toPresentation().timeline

        assertEquals(
            listOf(
                "步骤 1 · agent",
                "工具 · first-tool",
                "步骤 2 · agent",
                "工具 · second-tool",
                "工具 · orphan-first-tool",
                "工具 · orphan-second-tool",
            ),
            timeline.map { it.label },
        )
    }

    @Test
    fun presentationShowsOnlyChildLinkAndStructuredFinding() {
        val child = run(AgentRunStatus.SUCCEEDED.name).copy(
            id = "child",
            parentRunId = "run",
            summaryJson = JsonInstant.encodeToString(
                AgentRunSummary(childReport = ChildRunReport(findings = listOf("Safe finding")))
            ),
        )
        val presentation = AgentRunDetail(run(AgentRunStatus.RUNNING.name), emptyList(), emptyList(), emptyList(), listOf(child))
            .toPresentation()

        assertEquals("child", presentation.children.single().runId)
        assertEquals("Safe finding", presentation.children.single().findings)
    }

    @Test
    fun timelineMergesRedactedTraceEvents() {
        val trace = AgentTraceEvent(
            id = "trace",
            runId = "run",
            sequence = 0,
            type = AgentTraceEventType.POLICY_DECISION.name,
            status = AgentTraceStatus.DENIED.name,
            timestampMillis = 100,
            errorCategory = AgentTraceErrorCategory.POLICY.name,
            attributesJson = JsonInstant.encodeToString(AgentTraceAttributes()),
            createdAt = 100,
        )

        val timeline = AgentRunDetail(run(AgentRunStatus.RUNNING.name), emptyList(), emptyList(), emptyList(), traceEvents = listOf(trace))
            .toPresentation().timeline

        assertEquals("追踪 · POLICY DECISION", timeline.single().label)
        assertEquals("已拒绝", timeline.single().status)
        assertFalse(timeline.single().toString().contains("attributesJson"))
    }

    private fun run(
        status: String,
        config: AgentRunConfigSnapshot = AgentRunConfigSnapshot(),
        summary: AgentRunSummary? = null,
    ) = AgentRunEntity(
        id = "run",
        conversationId = "conversation",
        assistantId = "assistant",
        status = status,
        configSnapshotJson = JsonInstant.encodeToString(config),
        summaryJson = summary?.let(JsonInstant::encodeToString),
        createdAt = 0,
        updatedAt = 1_500,
    )

    private fun autoConfig(
        intent: AgentIntent,
        reasonCode: String,
        toolNames: List<String>,
    ) = AgentRunConfigSnapshot(
        modelId = "gpt-test",
        toolPolicyVersion = AgentRoutingSnapshot.CURRENT_VERSION,
        routing = AgentRoutingSnapshot.create(
            intent = intent,
            inputTrust = InputTrust.USER_DIRECT,
            reasonCode = reasonCode,
            resolvedToolNames = toolNames,
            permissionDigest = "policy:v1",
            executionContextDigest = "sha256:" + "a".repeat(64),
            providerIdleTimeoutMillis = 30_000,
            toolTimeoutMillis = 60_000,
            runTimeoutMillis = 120_000,
        ),
    )
}
