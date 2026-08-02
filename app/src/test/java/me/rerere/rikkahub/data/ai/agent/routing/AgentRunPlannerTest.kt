package me.rerere.rikkahub.data.ai.agent.routing

import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.ModelCapabilitySummary
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentRunPlannerTest {
    private val planner = AgentRunPlanner()

    @Test
    fun `new auto plan freezes a complete content-free snapshot`() {
        val mutableTools = mutableListOf("workspace_write_file", "artifact_read", "workspace_write_file")

        val plan = planner.planNewAuto(newRequest(resolvedToolNames = mutableTools))
        mutableTools.clear()

        assertEquals(AgentMode.AGENT, plan.mode)
        assertEquals("agent-loop-v3", plan.configSnapshot.runtimeVersion)
        assertEquals("conversation", plan.configSnapshot.conversationId)
        assertEquals("assistant", plan.configSnapshot.assistantId)
        assertEquals("model", plan.configSnapshot.modelId)
        assertEquals("provider", plan.configSnapshot.providerId)
        assertEquals("workspace", plan.configSnapshot.workspaceId)
        assertEquals(64, plan.configSnapshot.maxSteps)
        assertEquals(AgentRoutingSnapshot.CURRENT_VERSION, plan.configSnapshot.toolPolicyVersion)
        assertTrue(plan.configSnapshot.toolDescriptors.isEmpty())
        assertEquals(capabilities(), plan.configSnapshot.capabilitySummary)
        assertEquals(null, plan.configSnapshot.agentMode)
        assertEquals(AgentIntent.EXECUTE, plan.routing.intent)
        assertEquals(InputTrust.USER_DIRECT, plan.routing.inputTrust)
        assertEquals("explicit_mutation", plan.routing.reasonCode)
        assertEquals(PERMISSION_DIGEST, plan.routing.permissionDigest)
        assertEquals(EXECUTION_CONTEXT_DIGEST, plan.routing.executionContextDigest)
        assertEquals(45_000, plan.routing.providerIdleTimeoutMillis)
        assertEquals(30_000, plan.routing.toolTimeoutMillis)
        assertEquals(1_800_000, plan.routing.runTimeoutMillis)
        assertEquals(listOf("artifact_read", "workspace_write_file"), plan.routing.resolvedToolNames)
    }

    @Test
    fun `intent maps to legacy mode only at the compatibility boundary`() {
        val expected = mapOf(
            AgentIntent.ANSWER to AgentMode.CHAT,
            AgentIntent.EXPLORE to AgentMode.PLAN,
            AgentIntent.CLARIFY to AgentMode.PLAN,
            AgentIntent.EXECUTE to AgentMode.AGENT,
        )

        expected.forEach { (intent, mode) ->
            val plan = planner.planNewAuto(
                newRequest(
                    decision = IntentDecision(intent, "test_reason"),
                    resolvedToolNames = if (intent == AgentIntent.CLARIFY) emptyList() else listOf("artifact_read"),
                ),
            )

            assertEquals(mode, plan.mode)
            assertEquals(null, plan.configSnapshot.agentMode)
        }
    }

    @Test
    fun `resume restores the exact auto plan and ignores newly available tools`() {
        val original = planner.planNewAuto(newRequest())
        val request = resumeRequest(
            configSnapshotJson = AgentRoutingSnapshotCodec.encode(original.configSnapshot),
            availableToolNames = listOf("new_tool", "workspace_write_file", "artifact_read"),
        )

        val restored = assertAutoReady(planner.restoreContinuation(request))

        assertEquals(original, restored)
        assertEquals(listOf("artifact_read", "workspace_write_file"), restored.routing.resolvedToolNames)
    }

    @Test
    fun `resume blocks every identity capability policy and execution context drift`() {
        val encoded = AgentRoutingSnapshotCodec.encode(planner.planNewAuto(newRequest()).configSnapshot)
        val baseline = resumeRequest(encoded)
        val drifted = listOf(
            baseline.copy(conversationId = "other-conversation") to AgentRunPlanBlockReason.CONVERSATION_DRIFT,
            baseline.copy(assistantId = "other-assistant") to AgentRunPlanBlockReason.ASSISTANT_DRIFT,
            baseline.copy(modelId = "other-model") to AgentRunPlanBlockReason.MODEL_DRIFT,
            baseline.copy(providerId = "other-provider") to AgentRunPlanBlockReason.PROVIDER_DRIFT,
            baseline.copy(providerId = null) to AgentRunPlanBlockReason.PROVIDER_DRIFT,
            baseline.copy(workspaceId = "other-workspace") to AgentRunPlanBlockReason.WORKSPACE_DRIFT,
            baseline.copy(capabilitySummary = capabilities().copy(toolCalling = false)) to
                AgentRunPlanBlockReason.CAPABILITY_DRIFT,
            baseline.copy(capabilitySummary = null) to AgentRunPlanBlockReason.CAPABILITY_DRIFT,
            baseline.copy(permissionDigest = OTHER_PERMISSION_DIGEST) to AgentRunPlanBlockReason.PERMISSION_DIGEST_DRIFT,
            baseline.copy(permissionDigest = null) to AgentRunPlanBlockReason.PERMISSION_DIGEST_DRIFT,
            baseline.copy(executionContextDigest = OTHER_EXECUTION_CONTEXT_DIGEST) to
                AgentRunPlanBlockReason.EXECUTION_CONTEXT_DRIFT,
            baseline.copy(executionContextDigest = null) to AgentRunPlanBlockReason.EXECUTION_CONTEXT_DRIFT,
        )

        drifted.forEach { (request, expectedReason) ->
            assertBlocked(planner.restoreContinuation(request), expectedReason)
        }
    }

    @Test
    fun `resume blocks when any frozen tool cannot be rebuilt`() {
        val encoded = AgentRoutingSnapshotCodec.encode(planner.planNewAuto(newRequest()).configSnapshot)

        val blocked = assertBlocked(
            planner.restoreContinuation(
                resumeRequest(encoded, availableToolNames = listOf("artifact_read", "new_tool")),
            ),
            AgentRunPlanBlockReason.TOOL_MISSING,
        )

        assertEquals(listOf("workspace_write_file"), blocked.missingToolNames)
    }

    @Test
    fun `bad json unknown versions and incomplete auto configs are blocked without legacy fallback`() {
        assertBlocked(
            planner.restoreContinuation(resumeRequest("{not-json")),
            AgentRunPlanBlockReason.MALFORMED_SNAPSHOT,
        )

        val plan = planner.planNewAuto(newRequest())
        val unknownVersion = JsonInstant.encodeToString(
            plan.configSnapshot.copy(routing = plan.routing.copy(version = "auto-intent-v999")),
        )
        assertBlocked(
            planner.restoreContinuation(resumeRequest(unknownVersion)),
            AgentRunPlanBlockReason.UNSUPPORTED_VERSION,
        )

        listOf(
            plan.configSnapshot.copy(runtimeVersion = "agent-loop-v999"),
            plan.configSnapshot.copy(toolPolicyVersion = "auto-intent-v999"),
        ).forEach { unknownConfigVersion ->
            assertBlocked(
                planner.restoreContinuation(
                    resumeRequest(JsonInstant.encodeToString(unknownConfigVersion)),
                ),
                AgentRunPlanBlockReason.UNSUPPORTED_VERSION,
            )
        }

        listOf(
            plan.configSnapshot.copy(providerId = null),
            plan.configSnapshot.copy(capabilitySummary = null),
            plan.configSnapshot.copy(maxSteps = null),
            plan.configSnapshot.copy(agentMode = "AGENT"),
            plan.configSnapshot.copy(toolDescriptors = listOf("workspace_write_file")),
        ).forEach { incomplete ->
            assertBlocked(
                planner.restoreContinuation(resumeRequest(JsonInstant.encodeToString(incomplete))),
                AgentRunPlanBlockReason.INVALID_SNAPSHOT,
            )
        }
    }

    @Test
    fun `continuation keeps legal legacy modes explicit without upgrading them to auto`() {
        listOf("CHAT", "PLAN", "AGENT").forEach { mode ->
            val legacy = JsonInstant.encodeToString(
                AgentRunConfigSnapshot(
                    runtimeVersion = "agent-loop-v2",
                    conversationId = "conversation",
                    assistantId = "assistant",
                    modelId = "model",
                    agentMode = mode,
                ),
            )

            val result = planner.restoreContinuation(resumeRequest(legacy))

            assertTrue(result is AgentRunContinuationResult.LegacyReady)
            result as AgentRunContinuationResult.LegacyReady
            assertEquals(AgentMode.valueOf(mode), result.mode)
            assertEquals(mode, result.configSnapshot.agentMode)
        }
    }

    @Test
    fun `continuation requires an active root run with matching persisted identity`() {
        val encoded = AgentRoutingSnapshotCodec.encode(planner.planNewAuto(newRequest()).configSnapshot)
        val baseline = resumeRequest(encoded)
        val rejected = listOf(
            baseline.copy(parentRunId = "parent") to AgentRunPlanBlockReason.CHILD_RUN,
            baseline.copy(runStatus = AgentRunStatus.INTERRUPTED) to
                AgentRunPlanBlockReason.RUN_NOT_CONTINUABLE,
            baseline.copy(runConversationId = "other-conversation") to
                AgentRunPlanBlockReason.RUN_CONVERSATION_MISMATCH,
            baseline.copy(runAssistantId = "other-assistant") to
                AgentRunPlanBlockReason.RUN_ASSISTANT_MISMATCH,
        )

        rejected.forEach { (request, reason) ->
            assertBlocked(planner.restoreContinuation(request), reason)
        }

        assertAutoReady(
            planner.restoreContinuation(baseline.copy(runStatus = AgentRunStatus.WAITING_APPROVAL)),
        )
    }

    @Test
    fun `new auto plan rejects non digest execution context values`() {
        try {
            planner.planNewAuto(newRequest(executionContextDigest = "assistant prompt body"))
            fail("Raw execution context must never enter the snapshot")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `new auto plan rejects execute intent derived from untrusted input`() {
        try {
            planner.planNewAuto(newRequest().copy(inputTrust = InputTrust.DERIVED_UNTRUSTED))
            fail("Derived input must not authorize EXECUTE")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun newRequest(
        decision: IntentDecision = IntentDecision(AgentIntent.EXECUTE, "explicit_mutation"),
        resolvedToolNames: List<String> = listOf("workspace_write_file", "artifact_read"),
        executionContextDigest: String = EXECUTION_CONTEXT_DIGEST,
    ) = NewAgentRunPlanRequest(
        conversationId = "conversation",
        assistantId = "assistant",
        modelId = "model",
        providerId = "provider",
        workspaceId = "workspace",
        maxSteps = 64,
        capabilitySummary = capabilities(),
        decision = decision,
        inputTrust = InputTrust.USER_DIRECT,
        resolvedToolNames = resolvedToolNames,
        permissionDigest = PERMISSION_DIGEST,
        executionContextDigest = executionContextDigest,
        providerIdleTimeoutMillis = 45_000,
        toolTimeoutMillis = 30_000,
        runTimeoutMillis = 1_800_000,
    )

    private fun resumeRequest(
        configSnapshotJson: String,
        availableToolNames: List<String> = listOf("workspace_write_file", "artifact_read"),
    ) = AgentRunContinuationRequest(
        configSnapshotJson = configSnapshotJson,
        runId = "run",
        runConversationId = "conversation",
        runAssistantId = "assistant",
        parentRunId = null,
        runStatus = AgentRunStatus.RUNNING,
        conversationId = "conversation",
        assistantId = "assistant",
        modelId = "model",
        providerId = "provider",
        workspaceId = "workspace",
        capabilitySummary = capabilities(),
        availableToolNames = availableToolNames,
        permissionDigest = PERMISSION_DIGEST,
        executionContextDigest = EXECUTION_CONTEXT_DIGEST,
    )

    private fun assertAutoReady(result: AgentRunContinuationResult): AgentRunPlan {
        assertTrue(result is AgentRunContinuationResult.AutoReady)
        return (result as AgentRunContinuationResult.AutoReady).plan
    }

    private fun assertBlocked(
        result: AgentRunContinuationResult,
        reason: AgentRunPlanBlockReason,
    ): AgentRunContinuationResult.Blocked {
        assertTrue("Expected Blocked but was $result", result is AgentRunContinuationResult.Blocked)
        result as AgentRunContinuationResult.Blocked
        assertEquals(reason, result.reason)
        return result
    }

    private fun capabilities() = ModelCapabilitySummary(
        contextWindowTokens = 128_000,
        maxOutputTokens = 8_192,
        toolCalling = true,
        parallelToolCalls = true,
        streaming = true,
    )

    private companion object {
        const val PERMISSION_DIGEST =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_PERMISSION_DIGEST =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val EXECUTION_CONTEXT_DIGEST =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val OTHER_EXECUTION_CONTEXT_DIGEST =
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
