package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.AgentApprovalStatus
import me.rerere.rikkahub.data.model.AgentApprovalSummary
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.ChildRunBudgetSnapshot
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentStepSummary
import me.rerere.rikkahub.data.model.ToolExecutionStatus
import me.rerere.rikkahub.data.ai.agent.PersistedAgentRunRuntime
import me.rerere.rikkahub.data.ai.agent.permission.PolicyCode
import me.rerere.rikkahub.data.ai.agent.permission.PolicyDecision
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry
import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRunRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var time: MutableTimeSource
    private lateinit var repository: AgentRunRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        time = MutableTimeSource(100)
        repository = AgentRunRepository(database.agentRunDao(), database, time)
        database.conversationDao().insert(
            ConversationEntity(
                id = "conversation",
                assistantId = "assistant",
                title = "title",
                nodes = "[]",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun stateTransitionsAreConditionalAndApprovalsAreRecorded() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot(modelId = "model"))

        time.now = 110
        assertTrue(repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT))
        assertFalse(repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.RUNNING))
        time.now = 120
        assertTrue(repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING))

        repository.recordStep("step", "run", "model", summary = AgentStepSummary(kind = "model"))
        repository.recordToolExecution("tool", "run", "step", "safe_tool", "call", "hash")
        assertTrue(
            repository.requestApproval(
                "approval",
                "run",
                "tool",
                AgentApprovalSummary(
                    stepId = "step",
                    toolName = "safe_tool",
                    toolCallId = "call",
                    inputSha256 = "hash",
                    assistantId = "assistant",
                    mode = "AGENT",
                    policyDigest = "policy",
                    expiresAt = 1_000,
                ),
            )
        )
        assertTrue(repository.resolveApproval("approval", approved = true))

        assertEquals(ToolExecutionStatus.AUTHORIZED.name, database.agentRunDao().getToolExecution("tool")?.status)
        assertEquals(AgentApprovalStatus.APPROVED.name, repository.getApprovals("run").single().status)
        assertEquals(120, database.agentRunDao().getRun("run")?.startedAt)
    }

    @Test
    fun unsafeToolPersistenceIdentifiersAreRejected() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.recordStep("step", "run", "model")

        assertTrue(runCatching {
            repository.recordToolExecution(
                "tool", "run", "step", "workspace\nwrite", "call", "input-digest",
            )
        }.isFailure)
        assertTrue(runCatching {
            repository.recordToolExecution(
                "tool", "run", "step", "workspace_write", "call/with/path", "input-digest",
            )
        }.isFailure)
    }

    @Test
    fun observeLatestRunReturnsOnlyTheNewestConversationRun() = runBlocking {
        repository.createRun("first", "conversation", "assistant", AgentRunConfigSnapshot())
        time.now = 200
        repository.createRun("latest", "conversation", "assistant", AgentRunConfigSnapshot())

        assertEquals("latest", repository.observeLatestRun("conversation").first()?.id)
    }

    @Test
    fun observeLatestRunUsesIdAsStableTieBreaker() = runBlocking {
        repository.createRun("a", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.createRun("z", "conversation", "assistant", AgentRunConfigSnapshot())

        assertEquals("z", repository.observeLatestRun("conversation").first()?.id)
    }

    @Test
    fun cancellingAnOldRunNeverCancelsTheReplacementRun() = runBlocking {
        repository.createRun("old", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("old", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("old", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        time.now = 200
        repository.createRun("new", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("new", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("new", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)

        assertTrue(repository.cancelRun("old"))

        assertEquals(AgentRunStatus.CANCELLED.name, repository.getRun("old")?.status)
        assertEquals(AgentRunStatus.RUNNING.name, repository.getRun("new")?.status)
    }

    @Test
    fun exactApprovalResolutionDoesNotResolveSameToolCallInAnotherRun() = runBlocking {
        listOf("first", "second").forEach { runId ->
            repository.createRun(runId, "conversation", "assistant", AgentRunConfigSnapshot())
            repository.transitionRun(runId, setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
            repository.transitionRun(runId, setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
            repository.recordStep("$runId-step", runId, "model")
            repository.recordToolExecution("$runId-tool", runId, "$runId-step", "workspace_write_file", "same-call", "hash")
            assertTrue(repository.requestApproval(
                "$runId-approval", runId, "$runId-tool",
                AgentApprovalSummary(
                    stepId = "$runId-step", toolName = "workspace_write_file", toolCallId = "same-call",
                    inputSha256 = "hash", assistantId = "assistant", mode = "AGENT", policyDigest = "policy", expiresAt = 10_000,
                ),
            ))
        }

        assertTrue(repository.resolveApproval("second-approval", "second-tool", approved = true))
        assertEquals(AgentApprovalStatus.PENDING.name, repository.getApproval("first-approval")?.status)
        assertEquals(AgentApprovalStatus.APPROVED.name, repository.getApproval("second-approval")?.status)
    }

    @Test
    fun exactApprovalResolutionDoesNotResolveAnotherTurnWithTheSameToolCallId() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        listOf("first" to "hash-a", "second" to "hash-b").forEach { (stepId, inputHash) ->
            repository.recordStep(stepId, "run", "model")
            repository.recordToolExecution("$stepId-tool", "run", stepId, "workspace_write_file", "same-call", inputHash)
            assertTrue(repository.requestApproval(
                "$stepId-approval", "run", "$stepId-tool",
                AgentApprovalSummary(
                    stepId = stepId,
                    toolName = "workspace_write_file",
                    toolCallId = "same-call",
                    inputSha256 = inputHash,
                    assistantId = "assistant",
                    mode = "AGENT",
                    policyDigest = "policy",
                    expiresAt = 10_000,
                ),
            ))
        }

        assertTrue(repository.resolveApproval("second-approval", "second-tool", approved = true))

        assertEquals(AgentApprovalStatus.PENDING.name, repository.getApproval("first-approval")?.status)
        assertEquals(AgentApprovalStatus.APPROVED.name, repository.getApproval("second-approval")?.status)
    }

    @Test
    fun nestedRunsRecoveryAndConversationDeletionFollowPolicies() = runBlocking {
        repository.createRun("parent", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.createRun("child", "conversation", "assistant", AgentRunConfigSnapshot(), parentRunId = "parent")
        repository.recordStep("step", "child", "model")
        repository.recordToolExecution("tool", "child", "step", "safe_tool", "call", "hash")

        assertEquals("parent", database.agentRunDao().getRun("child")?.parentRunId)
        assertEquals(2, repository.interruptActiveRunsOnStartup())
        assertEquals(AgentRunStatus.INTERRUPTED.name, database.agentRunDao().getRun("child")?.status)
        assertEquals(
            ToolExecutionStatus.CANCELLED.name,
            database.agentRunDao().getToolExecution("tool")?.status,
        )

        database.conversationDao().deleteById("conversation")
        assertNull(database.agentRunDao().getRun("parent"))
        assertTrue(repository.getSteps("child").isEmpty())
        assertTrue(repository.getToolExecutions("child").isEmpty())
    }

    @Test
    fun controlledChildRunKeepsParentScopeBudgetAndCancellation() = runBlocking {
        repository.createRun("parent-controlled", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("parent-controlled", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("parent-controlled", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val snapshot = AgentRunConfigSnapshot(
            conversationId = "conversation",
            assistantId = "assistant",
            childBudget = ChildRunBudgetSnapshot(3, 4, 100, 1_000),
        )
        try {
            repository.createControlledChildRun(
                "invalid-cap", "parent-controlled", "conversation", "assistant", snapshot,
                maxChildren = 3, maxTotalTokens = 100, maxTotalDurationMillis = 1_000,
            )
            fail("Expected controlled child hard-cap rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("at most two children"))
        }
        repository.createControlledChildRun(
            "controlled-child", "parent-controlled", "conversation", "assistant", snapshot,
            maxChildren = 1, maxTotalTokens = 100, maxTotalDurationMillis = 1_000,
        )

        assertEquals("parent-controlled", repository.getRun("controlled-child")?.parentRunId)
        try {
            repository.createControlledChildRun(
                "too-many", "parent-controlled", "conversation", "assistant", snapshot,
                maxChildren = 1, maxTotalTokens = 200, maxTotalDurationMillis = 2_000,
            )
            fail("Expected child limit rejection")
        } catch (expected: IllegalArgumentException) {
            assertEquals("CHILD_LIMIT_EXCEEDED", expected.message)
        }
        try {
            repository.createRun("nested", "conversation", "assistant", snapshot, parentRunId = "controlled-child")
            fail("Expected nested child rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Nested child"))
        }

        assertTrue(repository.cancelRun("parent-controlled"))
        assertEquals(AgentRunStatus.CANCELLED.name, repository.getRun("controlled-child")?.status)
        try {
            repository.createControlledChildRun(
                "after-parent-cancel", "parent-controlled", "conversation", "assistant", snapshot,
                maxChildren = 2, maxTotalTokens = 200, maxTotalDurationMillis = 2_000,
            )
            fail("Expected inactive parent rejection")
        } catch (expected: IllegalArgumentException) {
            assertEquals("PARENT_RUN_NOT_ACTIVE", expected.message)
        }

        repository.createRun("parent-budget", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.createControlledChildRun(
            "budget-child", "parent-budget", "conversation", "assistant", snapshot,
            maxChildren = 2, maxTotalTokens = 150, maxTotalDurationMillis = 2_000,
        )
        try {
            repository.createControlledChildRun(
                "over-budget", "parent-budget", "conversation", "assistant", snapshot,
                maxChildren = 2, maxTotalTokens = 150, maxTotalDurationMillis = 2_000,
            )
            fail("Expected token budget rejection")
        } catch (expected: IllegalArgumentException) {
            assertEquals("CHILD_TOKEN_BUDGET_EXCEEDED", expected.message)
        }
        repository.failRun("parent-budget", "PARENT_FAILURE")
        assertEquals(AgentRunStatus.FAILED.name, repository.getRun("budget-child")?.status)
    }

    @Test
    fun approvalIsBoundToToolCallAndArgumentsAndContinuesTheSameRun() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val runtime = PersistedAgentRunRuntime(repository, "run")
        val step = runtime.stepStarted(0)
        val tool = UIMessagePart.Tool("call-1", "workspace_write_file", "{\"path\":\"a.txt\"}")
        val execution = runtime.toolObserved(step, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))
        val binding = AgentApprovalSummary(
            stepId = step,
            toolName = tool.toolName,
            toolCallId = tool.toolCallId,
            inputSha256 = "", // filled from the persisted execution below
            assistantId = "assistant",
            mode = "AGENT",
            policyDigest = "policy",
            expiresAt = 10_000,
        ).let { it.copy(inputSha256 = repository.getToolExecution(execution!!)?.inputSha256) }
        runtime.approvalRequested(
            execution,
            tool,
            PolicyDecision.Ask(PolicyCode.SIDE_EFFECT_REQUIRES_APPROVAL, "write"),
            binding,
        )
        runtime.waitingForApproval()

        val approvalId = repository.getApprovalForExecution(execution!!, AgentApprovalStatus.PENDING)!!.id
        assertFalse(runtime.approvalResolved(approvalId, "other-execution", approved = true))
        assertEquals(AgentRunStatus.WAITING_APPROVAL.name, database.agentRunDao().getRun("run")?.status)
        assertTrue(runtime.approvalResolved(approvalId, execution!!, approved = true))
        assertTrue(repository.transitionRun("run", setOf(AgentRunStatus.WAITING_APPROVAL), AgentRunStatus.RUNNING))
        assertTrue(runtime.approvedFor(execution, tool, binding))

        runtime.toolStarted(execution)
        runtime.toolFinished(execution, ToolExecutionStatus.SUCCEEDED, listOf(UIMessagePart.Text("secret output")))
        val persisted = database.agentRunDao().getToolExecution(execution!!)
        assertEquals(ToolExecutionStatus.SUCCEEDED.name, persisted?.status)
        assertFalse(persisted?.summaryJson.orEmpty().contains("a.txt"))
        assertFalse(persisted?.summaryJson.orEmpty().contains("secret output"))
    }

    @Test
    fun cancellationAndMaximumStepsReachTerminalRunStates() = runBlocking {
        repository.createRun("cancel", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("cancel", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("cancel", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        PersistedAgentRunRuntime(repository, "cancel").cancelled()
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentRunDao().getRun("cancel")?.status)

        repository.createRun("max", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("max", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("max", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        PersistedAgentRunRuntime(repository, "max").finished("max_steps")
        assertEquals(AgentRunStatus.FAILED.name, database.agentRunDao().getRun("max")?.status)
    }

    @Test
    fun continuationAllocatesSequencesAndRejectsDuplicateToolCallIds() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val runtime = PersistedAgentRunRuntime(repository, "run")
        val firstStep = runtime.stepStarted(0)
        val tool = UIMessagePart.Tool("call", "workspace_write_file", "{\"b\":2,\"a\":1}")
        val execution = runtime.toolObserved(firstStep, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))
        assertEquals(execution, runtime.toolObserved(firstStep, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName)))

        val binding = approvalBinding(firstStep, tool, execution!!)
        runtime.approvalRequested(execution, tool, PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "ask"), binding)
        runtime.waitingForApproval()
        assertTrue(runtime.approvalResolved(pendingApprovalId(execution!!), execution, true))
        assertTrue(repository.resumeRunAfterApproval("run"))
        val resumedStep = runtime.stepStarted(0)

        assertEquals(0, database.agentRunDao().getStep(firstStep)?.sequence)
        assertEquals(1, database.agentRunDao().getStep(resumedStep)?.sequence)
        val resumedExecution = runtime.toolObserved(resumedStep, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))
        assertEquals(execution, resumedExecution)
        assertTrue(runtime.approvedFor(resumedExecution, tool, binding.copy(stepId = resumedStep, expiresAt = 99_999)))
    }

    @Test
    fun approvalBindingExpiryAndContextChangesInvalidateAuthorization() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val runtime = PersistedAgentRunRuntime(repository, "run")
        val step = runtime.stepStarted(0)
        val tool = UIMessagePart.Tool("call", "workspace_write_file", "{\"path\":\"a\"}")
        val execution = runtime.toolObserved(step, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))!!
        val binding = approvalBinding(step, tool, execution)
        runtime.approvalRequested(execution, tool, PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "ask"), binding)
        assertTrue(runtime.approvalResolved(pendingApprovalId(execution), execution, true))
        assertTrue(runtime.approvedFor(execution, tool, binding))
        assertFalse(runtime.approvedFor(execution, tool, binding.copy(assistantId = "other")))
        assertFalse(runtime.approvedFor(execution, tool, binding.copy(workspaceId = "other")))
        assertFalse(runtime.approvedFor(execution, tool, binding.copy(policyDigest = "changed")))
        time.now = binding.expiresAt!!
        assertFalse(runtime.approvedFor(execution, tool, binding))
    }

    @Test
    fun cancellationConvergesWaitingApprovalWithoutGenerationJob() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val runtime = PersistedAgentRunRuntime(repository, "run")
        val step = runtime.stepStarted(0)
        val tool = UIMessagePart.Tool("call", "workspace_write_file", "{\"path\":\"a\"}")
        val execution = runtime.toolObserved(step, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))!!
        runtime.approvalRequested(execution, tool, PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "ask"), approvalBinding(step, tool, execution))
        runtime.waitingForApproval()

        assertTrue(repository.cancelActiveRun("conversation"))
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentRunDao().getRun("run")?.status)
        assertEquals(ToolExecutionStatus.CANCELLED.name, database.agentRunDao().getToolExecution(execution)?.status)
        assertEquals(AgentApprovalStatus.CANCELLED.name, repository.getApprovals("run").single().status)
    }

    @Test
    fun expiredApprovalReentersPendingOnTheOriginalExecutionWithoutCompletingTheRun() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val runtime = PersistedAgentRunRuntime(repository, "run")
        val originalStep = runtime.stepStarted(0)
        val tool = UIMessagePart.Tool("call", "workspace_write_file", "{\"path\":\"a\"}")
        val execution = runtime.toolObserved(originalStep, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))!!
        val originalBinding = approvalBinding(originalStep, tool, execution).copy(expiresAt = 200)
        assertTrue(runtime.approvalRequested(execution, tool, PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "ask"), originalBinding) != null)
        runtime.waitingForApproval()
        assertTrue(runtime.approvalResolved(pendingApprovalId(execution), execution, true))
        time.now = 150
        assertTrue(repository.resumeRunAfterApproval("run"))
        assertEquals(100, database.agentRunDao().getRun("run")?.startedAt)

        val continuationStep = runtime.stepStarted(1)
        val resumedExecution = runtime.toolObserved(continuationStep, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))
        assertEquals(execution, resumedExecution)
        time.now = 200
        assertFalse(runtime.approvedFor(resumedExecution, tool, originalBinding.copy(stepId = continuationStep, expiresAt = 10_000)))
        assertTrue(
            runtime.approvalRequested(
                resumedExecution,
                tool,
                PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "renew"),
                originalBinding.copy(stepId = continuationStep, expiresAt = 10_000),
            ) != null
        )
        runtime.stepFinished(continuationStep, me.rerere.rikkahub.data.model.AgentStepStatus.SUCCEEDED)
        runtime.waitingForApproval()

        assertEquals(AgentRunStatus.WAITING_APPROVAL.name, database.agentRunDao().getRun("run")?.status)
        assertEquals(ToolExecutionStatus.WAITING_APPROVAL.name, repository.getToolExecution(execution)?.status)
        assertEquals(
            listOf(AgentApprovalStatus.APPROVED.name, AgentApprovalStatus.PENDING.name),
            repository.getApprovals("run").map { it.status },
        )
    }

    @Test
    fun expiredDecisionReturnsReplacementThenApprovalExecutesOnTheSameRun() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val runtime = PersistedAgentRunRuntime(repository, "run")
        val step = runtime.stepStarted(0)
        val tool = UIMessagePart.Tool("call", "workspace_write_file", "{\"path\":\"a\"}")
        val execution = runtime.toolObserved(step, tool, ToolDescriptorRegistry.descriptorFor(tool.toolName))!!
        val expiredBinding = approvalBinding(step, tool, execution).copy(expiresAt = 150)
        assertTrue(runtime.approvalRequested(execution, tool, PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "ask"), expiredBinding) != null)
        runtime.waitingForApproval()

        time.now = 150
        val expiredApprovalId = pendingApprovalId(execution)
        val replacement = runtime.approvalResolution(expiredApprovalId, execution, true)

        assertFalse(replacement.resolved)
        assertTrue(replacement.replacementApprovalId != null)
        assertFalse(replacement.replacementApprovalId == expiredApprovalId)
        assertEquals(AgentRunStatus.WAITING_APPROVAL.name, repository.getRun("run")?.status)
        assertEquals(ToolExecutionStatus.WAITING_APPROVAL.name, repository.getToolExecution(execution)?.status)
        assertEquals(
            listOf(AgentApprovalStatus.CANCELLED.name, AgentApprovalStatus.PENDING.name),
            repository.getApprovals("run").map { it.status },
        )
        assertTrue(repository.getApprovals("run").last().summaryJson.orEmpty().contains("\"expiresAt\":300150"))

        assertTrue(runtime.approvalResolved(replacement.replacementApprovalId!!, execution, true))
        assertTrue(repository.resumeRunAfterApproval("run"))
        assertTrue(runtime.approvedFor(execution, tool, expiredBinding.copy(expiresAt = 300_150)))
        assertTrue(runtime.toolStarted(execution))
        runtime.toolFinished(execution, ToolExecutionStatus.SUCCEEDED)
        assertEquals(ToolExecutionStatus.SUCCEEDED.name, repository.getToolExecution(execution)?.status)
        assertFalse(repository.getApprovals("run").any { it.status == AgentApprovalStatus.PENDING.name })
    }

    @Test
    fun approvalResolutionConvergesApprovedAndDeniedExecutionStates() = runBlocking {
        repository.createRun("approved", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("approved", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("approved", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val approvedRuntime = PersistedAgentRunRuntime(repository, "approved")
        val approvedStep = approvedRuntime.stepStarted(0)
        val approvedTool = UIMessagePart.Tool("approved-call", "workspace_write_file", "{\"path\":\"a\"}")
        val approvedExecution = approvedRuntime.toolObserved(
            approvedStep, approvedTool, ToolDescriptorRegistry.descriptorFor(approvedTool.toolName),
        )!!
        assertTrue(approvedRuntime.approvalRequested(
            approvedExecution, approvedTool, PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "ask"),
            approvalBinding(approvedStep, approvedTool, approvedExecution),
        ) != null)
        approvedRuntime.waitingForApproval()
        assertTrue(approvedRuntime.approvalResolved(pendingApprovalId(approvedExecution), approvedExecution, true))
        assertTrue(repository.resumeRunAfterApproval("approved"))
        assertTrue(approvedRuntime.toolStarted(approvedExecution))
        approvedRuntime.toolFinished(approvedExecution, ToolExecutionStatus.SUCCEEDED)
        approvedRuntime.finished("no_tools")
        assertEquals(AgentRunStatus.SUCCEEDED.name, repository.getRun("approved")?.status)
        assertEquals(ToolExecutionStatus.SUCCEEDED.name, repository.getToolExecution(approvedExecution)?.status)
        assertFalse(repository.getApprovals("approved").any { it.status == AgentApprovalStatus.PENDING.name })

        repository.createRun("denied", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("denied", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("denied", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        val deniedRuntime = PersistedAgentRunRuntime(repository, "denied")
        val deniedStep = deniedRuntime.stepStarted(0)
        val deniedTool = UIMessagePart.Tool("denied-call", "workspace_write_file", "{\"path\":\"a\"}")
        val deniedExecution = deniedRuntime.toolObserved(
            deniedStep, deniedTool, ToolDescriptorRegistry.descriptorFor(deniedTool.toolName),
        )!!
        assertTrue(deniedRuntime.approvalRequested(
            deniedExecution, deniedTool, PolicyDecision.Ask(PolicyCode.DEFAULT_ASK, "ask"),
            approvalBinding(deniedStep, deniedTool, deniedExecution),
        ) != null)
        deniedRuntime.waitingForApproval()
        assertTrue(deniedRuntime.approvalResolved(pendingApprovalId(deniedExecution), deniedExecution, false))
        assertTrue(repository.resumeRunAfterApproval("denied"))
        deniedRuntime.finished("no_tools")
        assertEquals(AgentRunStatus.SUCCEEDED.name, repository.getRun("denied")?.status)
        assertEquals(ToolExecutionStatus.DENIED.name, repository.getToolExecution(deniedExecution)?.status)
        assertFalse(repository.getApprovals("denied").any { it.status == AgentApprovalStatus.PENDING.name })
    }

    @Test
    fun startupInterruptCancelsUnstartedToolsAndMarksOnlyPossibleExecutionsUnknown() = runBlocking {
        repository.createRun("run", "conversation", "assistant", AgentRunConfigSnapshot())
        repository.transitionRun("run", setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        repository.transitionRun("run", setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)
        repository.recordStep("step", "run", "model")
        repository.recordToolExecution("pending", "run", "step", "safe", "pending", "p", ToolExecutionStatus.PENDING)
        repository.recordToolExecution("waiting", "run", "step", "safe", "waiting", "w", ToolExecutionStatus.WAITING_APPROVAL)
        repository.recordToolExecution("authorized", "run", "step", "safe", "authorized", "a", ToolExecutionStatus.AUTHORIZED)
        repository.recordToolExecution("running", "run", "step", "safe", "running", "r", ToolExecutionStatus.RUNNING)

        repository.interruptActiveRunsOnStartup()

        assertEquals(ToolExecutionStatus.CANCELLED.name, repository.getToolExecution("pending")?.status)
        assertEquals(ToolExecutionStatus.CANCELLED.name, repository.getToolExecution("waiting")?.status)
        assertEquals(ToolExecutionStatus.UNKNOWN_AFTER_INTERRUPT.name, repository.getToolExecution("authorized")?.status)
        assertEquals(ToolExecutionStatus.UNKNOWN_AFTER_INTERRUPT.name, repository.getToolExecution("running")?.status)
    }

    private suspend fun approvalBinding(stepId: String, tool: UIMessagePart.Tool, executionId: String): AgentApprovalSummary =
        AgentApprovalSummary(
            stepId = stepId,
            toolName = tool.toolName,
            toolCallId = tool.toolCallId,
            inputSha256 = repository.getToolExecution(executionId)?.inputSha256,
            assistantId = "assistant",
            workspaceId = null,
            mode = "AGENT",
            policyDigest = "policy",
            expiresAt = 10_000,
        )

    private suspend fun pendingApprovalId(executionId: String): String =
        repository.getApprovalForExecution(executionId, AgentApprovalStatus.PENDING)!!.id

    private class MutableTimeSource(var now: Long) : AgentRunTimeSource {
        override fun nowMillis(): Long = now
    }
}
