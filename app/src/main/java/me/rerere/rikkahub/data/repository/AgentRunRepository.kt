package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.entity.AgentApprovalEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.AgentStepEntity
import me.rerere.rikkahub.data.db.entity.AgentTraceEvent
import me.rerere.rikkahub.data.db.entity.ToolExecutionEntity
import me.rerere.rikkahub.data.ai.agent.canonicalJson
import me.rerere.rikkahub.data.ai.agent.digest
import me.rerere.rikkahub.data.model.AgentApprovalStatus
import me.rerere.rikkahub.data.model.ApprovalResolution
import me.rerere.rikkahub.data.model.AgentApprovalSummary
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.ChildRunBudgetSnapshot
import me.rerere.rikkahub.data.model.AgentRunError
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentRunSummary
import me.rerere.rikkahub.data.model.AgentStepStatus
import me.rerere.rikkahub.data.model.AgentTraceAttributes
import me.rerere.rikkahub.data.model.AgentTraceErrorCategory
import me.rerere.rikkahub.data.model.AgentTraceEventType
import me.rerere.rikkahub.data.model.AgentTraceRedactor
import me.rerere.rikkahub.data.model.AgentTraceStatus
import me.rerere.rikkahub.data.model.AgentStepSummary
import me.rerere.rikkahub.data.model.ToolExecutionStatus
import me.rerere.rikkahub.data.model.ToolExecutionSummary
import me.rerere.rikkahub.utils.JsonInstant

fun interface AgentRunTimeSource {
    fun nowMillis(): Long
}

object SystemAgentRunTimeSource : AgentRunTimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Persistence boundary for agent telemetry. APIs accept only structured summaries, never prompts or tool bodies.
 */
class AgentRunRepository(
    private val dao: AgentRunDAO,
    private val database: AppDatabase,
    private val timeSource: AgentRunTimeSource = SystemAgentRunTimeSource,
) {
    private val traceRetentionMutex = Mutex()

    @Volatile
    private var startupRecovery = CompletableDeferred<Unit>().apply { complete(Unit) }

    /** Closes the generation gate before the application launches asynchronous recovery. */
    fun beginStartupRecovery() {
        synchronized(this) {
            startupRecovery = CompletableDeferred()
        }
    }

    suspend fun awaitStartupRecovery() = startupRecovery.await()
    fun observeRuns(conversationId: String): Flow<List<AgentRunEntity>> = dao.observeRunsForConversation(conversationId)

    fun observeLatestRun(conversationId: String): Flow<AgentRunEntity?> = dao.observeLatestRun(conversationId)

    fun observeActiveRun(conversationId: String): Flow<AgentRunEntity?> =
        dao.observeActiveRun(conversationId, AgentRunStatus.ACTIVE.runStatusNames())

    fun observeActiveRuns(): Flow<List<AgentRunEntity>> = dao.observeActiveRuns(AgentRunStatus.ACTIVE.runStatusNames())

    fun observeRun(id: String): Flow<AgentRunEntity?> = dao.observeRun(id)

    fun observeChildRuns(parentRunId: String): Flow<List<AgentRunEntity>> = dao.observeChildRuns(parentRunId)

    fun observeSteps(runId: String): Flow<List<AgentStepEntity>> = dao.observeSteps(runId)

    fun observeToolExecutions(runId: String): Flow<List<ToolExecutionEntity>> = dao.observeToolExecutions(runId)

    fun observeApprovals(runId: String): Flow<List<AgentApprovalEntity>> = dao.observeApprovals(runId)

    /** Run Center reads only redacted, fixed-schema events through this API. */
    fun observeTraceEvents(runId: String): Flow<List<AgentTraceEvent>> = database.agentTraceEventDao().observeForRun(runId)

    suspend fun getRuns(conversationId: String): List<AgentRunEntity> = dao.getRunsForConversation(conversationId)

    suspend fun getRun(id: String): AgentRunEntity? = dao.getRun(id)

    /** A child can inherit artifact access only from its direct parent in the same assistant and conversation. */
    suspend fun isAuthorizedParentArtifactRun(
        childRunId: String,
        parentRunId: String,
        assistantId: String,
        conversationId: String,
    ): Boolean {
        val child = dao.getRun(childRunId) ?: return false
        val parent = dao.getRun(parentRunId) ?: return false
        return child.parentRunId == parentRunId &&
            child.assistantId == assistantId && child.conversationId == conversationId &&
            parent.assistantId == assistantId && parent.conversationId == conversationId
    }

    suspend fun getApproval(id: String): AgentApprovalEntity? = dao.getApproval(id)

    suspend fun getActiveRun(conversationId: String): AgentRunEntity? =
        dao.getActiveRun(conversationId, AgentRunStatus.ACTIVE.runStatusNames())

    suspend fun getSteps(runId: String): List<AgentStepEntity> = dao.getSteps(runId)

    suspend fun getToolExecutions(runId: String): List<ToolExecutionEntity> = dao.getToolExecutions(runId)

    suspend fun getToolExecution(id: String): ToolExecutionEntity? = dao.getToolExecution(id)

    suspend fun getToolExecutionByIdentity(
        runId: String,
        stepId: String,
        toolName: String,
        toolCallId: String,
        inputSha256: String,
    ): ToolExecutionEntity? {
        validateToolIdentity(toolName, toolCallId, inputSha256)
        return dao.getToolExecutionByIdentity(runId, stepId, toolName, toolCallId, inputSha256)
    }

    suspend fun getLatestToolExecutionByCall(
        runId: String,
        toolName: String,
        toolCallId: String,
        inputSha256: String,
    ): ToolExecutionEntity? {
        validateToolIdentity(toolName, toolCallId, inputSha256)
        return dao.getLatestToolExecutionByCall(runId, toolName, toolCallId, inputSha256)
    }

    suspend fun hasToolCallInStep(runId: String, stepId: String, toolCallId: String): Boolean {
        requireSafeToolCallId(toolCallId)
        return dao.getToolExecutionByCallId(runId, stepId, toolCallId) != null
    }

    suspend fun getApprovals(runId: String): List<AgentApprovalEntity> = dao.getApprovals(runId)

    suspend fun getTraceEvents(runId: String): List<AgentTraceEvent> = database.agentTraceEventDao().getForRun(runId)

    /**
     * Appends a content-free event. A telemetry failure is deliberately reported as false rather
     * than propagated into agent execution.
     */
    suspend fun recordTrace(
        runId: String,
        type: AgentTraceEventType,
        status: AgentTraceStatus,
        attributes: AgentTraceAttributes = AgentTraceAttributes(),
        errorCategory: AgentTraceErrorCategory = AgentTraceErrorCategory.NONE,
        timestampMillis: Long = timeSource.nowMillis(),
        durationMillis: Long? = null,
    ): Boolean = try {
        require(timestampMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
        val encodedAttributes = AgentTraceRedactor.encodeAttributes(attributes)
        require(encodedAttributes.toByteArray(Charsets.UTF_8).size <= MAX_TRACE_ATTRIBUTES_BYTES)
        val recorded = database.withTransaction {
            val traceDao = database.agentTraceEventDao()
            if (dao.getRun(runId) == null) return@withTransaction false
            val sequence = traceDao.nextSequence(runId)
            traceDao.insert(
                AgentTraceEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = sequence,
                    type = type.name,
                    status = status.name,
                    timestampMillis = timestampMillis,
                    durationMillis = durationMillis,
                    errorCategory = errorCategory.name,
                    attributesJson = encodedAttributes,
                    createdAt = timestampMillis,
                ),
            )
            trimRunTrace(traceDao, runId, sequence, timestampMillis)
            true
        }
        if (recorded) cleanupTraceRetention(timestampMillis)
        recorded
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        false
    }

    suspend fun getApprovalForExecution(
        toolExecutionId: String,
        status: AgentApprovalStatus,
    ): AgentApprovalEntity? = dao.getApprovalForExecution(toolExecutionId, status.name)

    suspend fun getPendingApprovalsByToolIdentity(
        runId: String,
        toolName: String,
        inputSha256: String,
    ): List<AgentApprovalEntity> = dao.getPendingApprovalsByToolIdentity(
        runId,
        toolName,
        inputSha256,
        AgentApprovalStatus.PENDING.name,
        ToolExecutionStatus.WAITING_APPROVAL.name,
    )

    suspend fun createRun(
        id: String,
        conversationId: String,
        assistantId: String,
        configSnapshot: AgentRunConfigSnapshot,
        parentRunId: String? = null,
        initialStatus: AgentRunStatus = AgentRunStatus.QUEUED,
    ): AgentRunEntity {
        require(initialStatus == AgentRunStatus.QUEUED) { "Runs must start QUEUED" }
        require(parentRunId != id) { "A run cannot be its own parent" }
        val now = timeSource.nowMillis()
        val run = database.withTransaction {
            parentRunId?.let { parentId ->
                val parent = requireNotNull(dao.getRun(parentId)) { "Parent run does not exist" }
                require(parent.parentRunId == null) { "Nested child runs are not allowed" }
                require(parent.conversationId == conversationId && parent.assistantId == assistantId) {
                    "Child run must belong to its parent conversation and assistant"
                }
            }
            AgentRunEntity(
                id = id,
                conversationId = conversationId,
                assistantId = assistantId,
                parentRunId = parentRunId,
                status = initialStatus.name,
                configSnapshotJson = encodeConfig(configSnapshot),
                createdAt = now,
                updatedAt = now,
            ).also(dao::insertRun)
        }
        recordTrace(run.id, AgentTraceEventType.RUN_STARTED, AgentTraceStatus.STARTED)
        parentRunId?.let { parentId ->
            recordTrace(
                parentId,
                AgentTraceEventType.CHILD_RUN,
                AgentTraceStatus.STARTED,
                AgentTraceAttributes(childRunIdHash = me.rerere.rikkahub.data.model.AgentTraceRedactor.hash(run.id)),
            )
        }
        return run
    }

    /** Atomically reserves a direct child slot and its declared token/duration budgets. */
    suspend fun createControlledChildRun(
        id: String,
        parentRunId: String,
        conversationId: String,
        assistantId: String,
        configSnapshot: AgentRunConfigSnapshot,
        maxChildren: Int,
        maxTotalTokens: Int,
        maxTotalDurationMillis: Long,
        maxConcurrentChildren: Int = maxChildren,
    ): AgentRunEntity {
        val budget = requireNotNull(configSnapshot.childBudget) { "Controlled children require a budget snapshot" }
        require(maxChildren in 1..2) { "Controlled Explore supports at most two children per parent" }
        require(maxConcurrentChildren in 1..2) { "Controlled Explore supports one or two concurrent children" }
        val run = database.withTransaction {
            val parent = requireNotNull(dao.getRun(parentRunId)) { "Parent run does not exist" }
            require(parent.parentRunId == null) { "Nested child runs are not allowed" }
            require(parent.status in AgentRunStatus.ACTIVE.map(AgentRunStatus::name)) { "PARENT_RUN_NOT_ACTIVE" }
            require(parent.conversationId == conversationId && parent.assistantId == assistantId) {
                "Child run must belong to its parent conversation and assistant"
            }
            val children = dao.getChildRuns(parentRunId)
            require(children.size < maxChildren) { "CHILD_LIMIT_EXCEEDED" }
            require(children.count { it.status in AgentRunStatus.ACTIVE.map(AgentRunStatus::name) } < maxConcurrentChildren) {
                "CHILD_CONCURRENCY_LIMIT_EXCEEDED"
            }
            val budgets = children.mapNotNull { child ->
                runCatching { JsonInstant.decodeFromString<AgentRunConfigSnapshot>(child.configSnapshotJson).childBudget }.getOrNull()
            }
            require(budgets.sumOf(ChildRunBudgetSnapshot::maxOutputTokens) + budget.maxOutputTokens <= maxTotalTokens) {
                "CHILD_TOKEN_BUDGET_EXCEEDED"
            }
            require(budgets.sumOf(ChildRunBudgetSnapshot::maxDurationMillis) + budget.maxDurationMillis <= maxTotalDurationMillis) {
                "CHILD_DURATION_BUDGET_EXCEEDED"
            }
            val now = timeSource.nowMillis()
            AgentRunEntity(
                id = id,
                conversationId = conversationId,
                assistantId = assistantId,
                parentRunId = parentRunId,
                status = AgentRunStatus.QUEUED.name,
                configSnapshotJson = encodeConfig(configSnapshot),
                createdAt = now,
                updatedAt = now,
            ).also(dao::insertRun)
        }
        recordTrace(run.id, AgentTraceEventType.RUN_STARTED, AgentTraceStatus.STARTED)
        recordTrace(
            parentRunId,
            AgentTraceEventType.CHILD_RUN,
            AgentTraceStatus.STARTED,
            AgentTraceAttributes(childRunIdHash = me.rerere.rikkahub.data.model.AgentTraceRedactor.hash(run.id)),
        )
        return run
    }

    suspend fun updateRunSummary(runId: String, summary: AgentRunSummary) {
        database.withTransaction { dao.updateRunSummary(runId, encodeSummary(summary), timeSource.nowMillis()) }
    }

    /** Atomically retires any prior active run before a normal generation starts a fresh one. */
    suspend fun replaceActiveRun(
        id: String,
        conversationId: String,
        assistantId: String,
        configSnapshot: AgentRunConfigSnapshot,
    ): AgentRunEntity {
        val run = database.withTransaction {
            val now = timeSource.nowMillis()
            dao.getActiveRunsForConversation(conversationId, AgentRunStatus.ACTIVE.runStatusNames()).forEach {
                convergeRun(it.id, AgentRunStatus.CANCELLED, AgentStepStatus.CANCELLED, ToolExecutionStatus.CANCELLED, now)
            }
            AgentRunEntity(
                id = id,
                conversationId = conversationId,
                assistantId = assistantId,
                status = AgentRunStatus.QUEUED.name,
                configSnapshotJson = encodeConfig(configSnapshot),
                createdAt = now,
                updatedAt = now,
            ).also(dao::insertRun)
        }
        // This occurs after the state transaction: trace loss must not roll back a new run.
        recordTrace(run.id, AgentTraceEventType.RUN_STARTED, AgentTraceStatus.STARTED)
        return run
    }

    suspend fun transitionRun(
        id: String,
        expectedStatuses: Set<AgentRunStatus>,
        newStatus: AgentRunStatus,
        error: AgentRunError? = null,
        summary: AgentRunSummary? = null,
    ): Boolean {
        validateRunTransition(expectedStatuses, newStatus)
        val now = timeSource.nowMillis()
        val transitioned = dao.transitionRun(
            id = id,
            expectedStatuses = expectedStatuses.runStatusNames(),
            newStatus = newStatus.name,
            errorJson = error?.let(::encodeError),
            summaryJson = summary?.let(::encodeSummary),
            updatedAt = now,
            // A resumed WAITING_APPROVAL run must retain its original start time.
            startedAt = now.takeIf { newStatus == AgentRunStatus.RUNNING },
            finishedAt = now.takeIf { newStatus.isTerminal },
        ) == 1
        if (transitioned) {
            when {
                newStatus == AgentRunStatus.PREFLIGHT -> recordTrace(id, AgentTraceEventType.PREFLIGHT, AgentTraceStatus.STARTED)
                newStatus.isTerminal -> recordRunFinished(id, newStatus, error?.category)
            }
        }
        return transitioned
    }

    suspend fun recordStep(
        id: String,
        runId: String,
        kind: String,
        status: AgentStepStatus = AgentStepStatus.PENDING,
        summary: AgentStepSummary? = null,
    ): AgentStepEntity {
        val now = timeSource.nowMillis()
        val step = database.withTransaction {
            val step = AgentStepEntity(
                id = id,
                runId = runId,
                sequence = dao.nextStepSequence(runId),
                kind = kind,
                status = status.name,
                summaryJson = summary?.let(::encodeStepSummary),
                createdAt = now,
                updatedAt = now,
                finishedAt = now.takeIf { status.isTerminal },
            )
            dao.insertStep(step)
            step
        }
        recordTrace(
            runId,
            AgentTraceEventType.CHECKPOINT,
            if (status == AgentStepStatus.FAILED) AgentTraceStatus.FAILED else AgentTraceStatus.STARTED,
            AgentTraceAttributes(stepIndex = step.sequence),
        )
        return step
    }

    suspend fun recordToolExecution(
        id: String,
        runId: String,
        stepId: String,
        toolName: String,
        toolCallId: String,
        inputSha256: String,
        status: ToolExecutionStatus = ToolExecutionStatus.PENDING,
        summary: ToolExecutionSummary? = null,
    ): ToolExecutionEntity {
        validateToolIdentity(toolName, toolCallId, inputSha256)
        summary?.let(::validateToolSummary)
        require(summary?.toolCallId == null || summary.toolCallId == toolCallId) { "Tool summary call ID does not match" }
        require(summary?.inputSha256 == null || summary.inputSha256 == inputSha256) { "Tool summary input digest does not match" }
        val now = timeSource.nowMillis()
        return database.withTransaction {
            require(dao.getStep(stepId)?.runId == runId) { "Tool execution step must belong to its run" }
            require(dao.getToolExecutionByCallId(runId, stepId, toolCallId) == null) {
                "Duplicate toolCallId '$toolCallId' in the same model turn"
            }
            val execution = ToolExecutionEntity(
                id = id,
                runId = runId,
                stepId = stepId,
                sequence = dao.nextToolExecutionSequence(runId),
                toolName = toolName,
                toolCallId = toolCallId,
                inputSha256 = inputSha256,
                status = status.name,
                summaryJson = summary?.let(::encodeToolSummary),
                createdAt = now,
                updatedAt = now,
                finishedAt = now.takeIf { !status.isActive },
            )
            dao.insertToolExecution(execution)
            execution
        }
    }

    suspend fun transitionStep(
        id: String,
        expectedStatuses: Set<AgentStepStatus>,
        newStatus: AgentStepStatus,
        summary: AgentStepSummary? = null,
    ): Boolean {
        val now = timeSource.nowMillis()
        return dao.transitionStep(
            id = id,
            expectedStatuses = expectedStatuses.map(AgentStepStatus::name),
            newStatus = newStatus.name,
            summaryJson = summary?.let(::encodeStepSummary),
            updatedAt = now,
            finishedAt = now.takeIf { newStatus.isTerminal },
        ) == 1
    }

    suspend fun transitionToolExecution(
        id: String,
        expectedStatuses: Set<ToolExecutionStatus>,
        newStatus: ToolExecutionStatus,
        error: AgentRunError? = null,
        summary: ToolExecutionSummary? = null,
    ): Boolean {
        validateToolTransition(expectedStatuses, newStatus)
        summary?.let(::validateToolSummary)
        val now = timeSource.nowMillis()
        return dao.transitionToolExecution(
            id = id,
            expectedStatuses = expectedStatuses.toolStatusNames(),
            newStatus = newStatus.name,
            errorJson = error?.let(::encodeError),
            summaryJson = summary?.let(::encodeToolSummary),
            updatedAt = now,
            startedAt = now.takeIf { newStatus == ToolExecutionStatus.RUNNING },
            finishedAt = now.takeIf { !newStatus.isActive },
        ) == 1
    }

    suspend fun requestApproval(
        id: String,
        runId: String,
        toolExecutionId: String,
        summary: AgentApprovalSummary? = null,
    ): Boolean {
        val now = timeSource.nowMillis()
        return database.withTransaction {
            val execution = dao.getToolExecution(toolExecutionId)
            if (execution?.runId != runId) return@withTransaction false
            val binding = summary ?: return@withTransaction false
            if (
                binding.stepId != execution.stepId ||
                binding.toolName != execution.toolName ||
                binding.toolCallId != execution.toolCallId ||
                binding.inputSha256 != execution.inputSha256 ||
                binding.assistantId.isNullOrBlank() ||
                binding.mode.isNullOrBlank() ||
                binding.policyDigest.isNullOrBlank() ||
                binding.expiresAt == null || binding.expiresAt <= now
            ) return@withTransaction false
            val waiting = dao.transitionToolExecution(
                id = toolExecutionId,
                expectedStatuses = setOf(ToolExecutionStatus.PENDING, ToolExecutionStatus.AUTHORIZED).toolStatusNames(),
                newStatus = ToolExecutionStatus.WAITING_APPROVAL.name,
                errorJson = null,
                summaryJson = execution.summaryJson,
                updatedAt = now,
                startedAt = null,
                finishedAt = null,
            ) == 1
            if (!waiting) return@withTransaction false
            dao.insertApproval(
                AgentApprovalEntity(
                    id = id,
                    runId = runId,
                    toolExecutionId = toolExecutionId,
                    sequence = dao.nextApprovalSequence(runId),
                    status = AgentApprovalStatus.PENDING.name,
                    summaryJson = encodeApprovalSummary(binding),
                    createdAt = now,
                )
            )
            true
        }
    }

    suspend fun resolveApproval(id: String, approved: Boolean): Boolean =
        resolveApprovalWithReplacement(id, approved).resolved

    suspend fun resolveApprovalWithReplacement(id: String, approved: Boolean): ApprovalResolution {
        val now = timeSource.nowMillis()
        return database.withTransaction {
            val approval = dao.getApproval(id) ?: return@withTransaction ApprovalResolution()
            val binding = approval.summaryJson?.let {
                runCatching { JsonInstant.decodeFromString<AgentApprovalSummary>(it) }.getOrNull()
            } ?: return@withTransaction ApprovalResolution()
            if (approval.status != AgentApprovalStatus.PENDING.name) return@withTransaction ApprovalResolution()
            if ((binding.expiresAt ?: 0) <= now) {
                // The replacement remains attached to the original execution and waiting run.
                if (dao.resolveApproval(
                        id, AgentApprovalStatus.PENDING.name, AgentApprovalStatus.CANCELLED.name, now,
                    ) != 1) return@withTransaction ApprovalResolution()
                val replacementId = java.util.UUID.randomUUID().toString()
                dao.insertApproval(
                    AgentApprovalEntity(
                        id = replacementId,
                        runId = approval.runId,
                        toolExecutionId = approval.toolExecutionId,
                        sequence = dao.nextApprovalSequence(approval.runId),
                        status = AgentApprovalStatus.PENDING.name,
                        summaryJson = encodeApprovalSummary(binding.copy(expiresAt = now + APPROVAL_TTL_MILLIS)),
                        createdAt = now,
                    )
                )
                return@withTransaction ApprovalResolution(replacementApprovalId = replacementId)
            }
            val toolStatus = if (approved) ToolExecutionStatus.AUTHORIZED else ToolExecutionStatus.DENIED
            val toolUpdated = dao.transitionToolExecution(
                id = approval.toolExecutionId,
                expectedStatuses = setOf(ToolExecutionStatus.WAITING_APPROVAL).toolStatusNames(),
                newStatus = toolStatus.name,
                errorJson = null,
                summaryJson = null,
                updatedAt = now,
                startedAt = null,
                finishedAt = now.takeIf { toolStatus != ToolExecutionStatus.AUTHORIZED },
            ) == 1
            if (!toolUpdated) return@withTransaction ApprovalResolution()
            ApprovalResolution(resolved = dao.resolveApproval(
                id = id,
                expectedStatus = AgentApprovalStatus.PENDING.name,
                status = if (approved) AgentApprovalStatus.APPROVED.name else AgentApprovalStatus.DENIED.name,
                resolvedAt = now,
            ) == 1)
        }
    }

    suspend fun resolveApproval(id: String, toolExecutionId: String, approved: Boolean): Boolean {
        return database.withTransaction {
            val approval = dao.getApproval(id) ?: return@withTransaction false
            if (approval.toolExecutionId != toolExecutionId) return@withTransaction false
            resolveApproval(id, approved)
        }
    }

    suspend fun cancelActiveRun(conversationId: String): Boolean {
        return database.withTransaction {
            val run = dao.getActiveRun(conversationId, AgentRunStatus.ACTIVE.runStatusNames()) ?: return@withTransaction false
            convergeRun(run.id, AgentRunStatus.CANCELLED, AgentStepStatus.CANCELLED, ToolExecutionStatus.CANCELLED, timeSource.nowMillis())
        }
    }

    suspend fun cancelRun(runId: String): Boolean = database.withTransaction {
        convergeRun(runId, AgentRunStatus.CANCELLED, AgentStepStatus.CANCELLED, ToolExecutionStatus.CANCELLED, timeSource.nowMillis())
    }

    /** Marks in-flight work as interrupted after a process restart; it never attempts to resume tool calls. */
    suspend fun interruptActiveRunsOnStartup(): Int {
        return try {
            val now = timeSource.nowMillis()
            database.withTransaction {
                dao.getActiveRuns(AgentRunStatus.ACTIVE.runStatusNames()).count { run ->
                    interruptRun(run.id, now)
                }
            }
        } finally {
            startupRecovery.complete(Unit)
        }
    }

    suspend fun failRun(runId: String, code: String, category: String = "preflight") {
        database.withTransaction {
            convergeRun(runId, AgentRunStatus.FAILED, AgentStepStatus.FAILED, ToolExecutionStatus.FAILED, timeSource.nowMillis(), code, category)
        }
    }

    suspend fun blockRun(runId: String, code: String, category: String = "preflight") {
        database.withTransaction {
            convergeRun(runId, AgentRunStatus.BLOCKED, AgentStepStatus.SKIPPED, ToolExecutionStatus.CANCELLED, timeSource.nowMillis(), code, category)
        }
    }

    suspend fun blockRun(runId: String, error: AgentRunError, summary: AgentRunSummary) {
        val now = timeSource.nowMillis()
        val transitioned = database.withTransaction {
            dao.terminateActiveSteps(runId, AgentStepStatus.ACTIVE.map(AgentStepStatus::name), AgentStepStatus.SKIPPED.name, now)
            dao.transitionRun(
                runId, AgentRunStatus.ACTIVE.runStatusNames(), AgentRunStatus.BLOCKED.name,
                encodeError(error), encodeSummary(summary), now, null, now,
            ) == 1
        }
        if (transitioned) recordRunFinished(runId, AgentRunStatus.BLOCKED, error.category)
    }

    suspend fun resumeRunAfterApproval(runId: String): Boolean = database.withTransaction {
        val pending = dao.getApprovals(runId).any { it.status == AgentApprovalStatus.PENDING.name }
        if (pending) return@withTransaction false
        dao.transitionRun(
            runId, listOf(AgentRunStatus.WAITING_APPROVAL.name), AgentRunStatus.RUNNING.name,
            null, null, timeSource.nowMillis(), timeSource.nowMillis(), null,
        ) == 1
    }

    suspend fun authorizationFor(
        runId: String,
        toolExecutionId: String,
        tool: me.rerere.ai.ui.UIMessagePart.Tool,
        binding: AgentApprovalSummary,
    ): Boolean {
        val execution = dao.getToolExecution(toolExecutionId) ?: return false
        val approval = dao.getApprovalForExecution(toolExecutionId, AgentApprovalStatus.APPROVED.name) ?: return false
        val stored = approval.summaryJson?.let { runCatching { JsonInstant.decodeFromString<AgentApprovalSummary>(it) }.getOrNull() }
            ?: return false
        val now = timeSource.nowMillis()
        return approval.runId == runId && execution.runId == runId &&
            execution.status == ToolExecutionStatus.AUTHORIZED.name &&
            execution.stepId == stored.stepId &&
            execution.toolName == stored.toolName && execution.toolName == tool.toolName &&
            execution.toolCallId == stored.toolCallId && execution.toolCallId == tool.toolCallId &&
            execution.inputSha256 == stored.inputSha256 &&
            execution.inputSha256 == tool.input.canonicalJson().digest() &&
            stored.assistantId == binding.assistantId &&
            stored.workspaceId == binding.workspaceId &&
            stored.mode == binding.mode &&
            stored.policyDigest == binding.policyDigest &&
            // A continuation has a different step. Only this persisted expiry is authoritative.
            (stored.expiresAt ?: 0) > now
    }

    private suspend fun convergeRun(
        runId: String,
        runStatus: AgentRunStatus,
        stepStatus: AgentStepStatus,
        toolStatus: ToolExecutionStatus,
        now: Long,
        errorCode: String = runStatus.name,
        category: String = "lifecycle",
    ): Boolean {
        // Child failure is isolated, but a parent lifecycle transition must stop every active direct child.
        dao.getChildRuns(runId).filter { it.status in AgentRunStatus.ACTIVE.map(AgentRunStatus::name) }.forEach { child ->
            convergeRun(child.id, runStatus, stepStatus, toolStatus, now, errorCode, category)
        }
        dao.terminateActiveSteps(runId, AgentStepStatus.ACTIVE.map(AgentStepStatus::name), stepStatus.name, now)
        dao.terminateActiveToolExecutions(runId, ToolExecutionStatus.ACTIVE.toolStatusNames(), toolStatus.name, now)
        dao.cancelPendingApprovals(runId, AgentApprovalStatus.PENDING.name, AgentApprovalStatus.CANCELLED.name, now)
        val transitioned = dao.transitionRun(
            runId, AgentRunStatus.ACTIVE.runStatusNames(), runStatus.name,
            encodeError(AgentRunError(errorCode, category)), null, now, null, now,
        ) == 1
        if (transitioned) recordRunFinished(runId, runStatus, category)
        return transitioned
    }

    private suspend fun interruptRun(runId: String, now: Long): Boolean {
        dao.terminateActiveSteps(runId, AgentStepStatus.ACTIVE.map(AgentStepStatus::name), AgentStepStatus.CANCELLED.name, now)
        dao.terminateActiveToolExecutions(
            runId,
            setOf(ToolExecutionStatus.PENDING, ToolExecutionStatus.WAITING_APPROVAL).toolStatusNames(),
            ToolExecutionStatus.CANCELLED.name,
            now,
        )
        dao.terminateActiveToolExecutions(
            runId,
            setOf(ToolExecutionStatus.AUTHORIZED, ToolExecutionStatus.RUNNING).toolStatusNames(),
            ToolExecutionStatus.UNKNOWN_AFTER_INTERRUPT.name,
            now,
        )
        dao.cancelPendingApprovals(runId, AgentApprovalStatus.PENDING.name, AgentApprovalStatus.CANCELLED.name, now)
        val transitioned = dao.transitionRun(
            runId, AgentRunStatus.ACTIVE.runStatusNames(), AgentRunStatus.INTERRUPTED.name,
            encodeError(AgentRunError("PROCESS_INTERRUPTED", "lifecycle")), null, now, null, now,
        ) == 1
        if (transitioned) recordRunFinished(runId, AgentRunStatus.INTERRUPTED, "lifecycle")
        return transitioned
    }

    private fun validateRunTransition(expected: Set<AgentRunStatus>, target: AgentRunStatus) {
        require(expected.isNotEmpty()) { "Expected run statuses cannot be empty" }
        require(expected.all { it.canTransitionTo(target) }) { "Invalid run status transition to $target" }
    }

    private fun validateToolTransition(expected: Set<ToolExecutionStatus>, target: ToolExecutionStatus) {
        require(expected.isNotEmpty()) { "Expected tool statuses cannot be empty" }
        require(expected.all { it.canTransitionTo(target) }) { "Invalid tool status transition to $target" }
    }

    private fun encodeConfig(value: AgentRunConfigSnapshot): String = encodeBounded(value, MAX_CONFIG_JSON_BYTES)

    private fun encodeError(value: AgentRunError): String = encodeBounded(value, MAX_ERROR_JSON_BYTES)

    private fun encodeSummary(value: AgentRunSummary): String = encodeBounded(value, MAX_SUMMARY_JSON_BYTES)

    private fun encodeStepSummary(value: AgentStepSummary): String = encodeBounded(value, MAX_SUMMARY_JSON_BYTES)

    private fun encodeToolSummary(value: ToolExecutionSummary): String = encodeBounded(value, MAX_SUMMARY_JSON_BYTES)

    private fun encodeApprovalSummary(value: AgentApprovalSummary): String =
        encodeBounded(value, MAX_SUMMARY_JSON_BYTES)

    private fun validateToolSummary(summary: ToolExecutionSummary) {
        summary.toolCallId?.let(::requireSafeToolCallId)
        summary.inputSha256?.let(::requireSafeInputDigest)
        summary.outputSha256?.let(::requireSafeInputDigest)
        summary.outputArtifactId?.let(::requireSafeArtifactId)
        require(summary.outputPreview == null) { "Tool output previews must not enter persistence" }
        require(summary.inputBytes == null || summary.inputBytes in 0..MAX_TOOL_BYTES)
        require(summary.outputBytes == null || summary.outputBytes in 0..MAX_TOOL_BYTES)
    }

    private inline fun <reified T> encodeBounded(value: T, maxBytes: Int): String {
        val encoded = JsonInstant.encodeToString(value)
        require(encoded.toByteArray(Charsets.UTF_8).size <= maxBytes) { "Agent run JSON field exceeds $maxBytes bytes" }
        return encoded
    }

    private suspend fun cleanupTraceRetention(nowMillis: Long) {
        try {
            traceRetentionMutex.withLock {
                database.withTransaction {
                    val traceDao = database.agentTraceEventDao()
                    traceDao.deleteOlderThan(nowMillis - TRACE_RETENTION_MILLIS)
                    traceDao.trimToTotal(MAX_TRACE_EVENTS_TOTAL)
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Trace retention is best-effort and cannot change a successful run into a failure.
        }
    }

    /**
     * Keep the immutable lifecycle anchor and trim only on a completed-step boundary when one is
     * available. Replacing that boundary with TRACE_TRUNCATED lets replay resume deterministically
     * without pretending that a provider's call ID was durable.
     */
    private suspend fun trimRunTrace(
        traceDao: me.rerere.rikkahub.data.db.dao.AgentTraceEventDAO,
        runId: String,
        latestSequence: Int,
        timestampMillis: Long,
    ) {
        if (latestSequence < MAX_TRACE_EVENTS_PER_RUN) return
        val preferredBoundary = latestSequence - MAX_TRACE_EVENTS_PER_RUN + 2
        val boundary = traceDao.getForRun(runId)
            .lastOrNull { it.type == AgentTraceEventType.CHECKPOINT.name && it.sequence <= preferredBoundary }
            ?.sequence ?: preferredBoundary
        traceDao.deleteRunEventsInRangeExceptAnchors(runId, 1, boundary)
        traceDao.insert(
            AgentTraceEvent(
                id = java.util.UUID.randomUUID().toString(),
                runId = runId,
                sequence = boundary,
                type = AgentTraceEventType.TRACE_TRUNCATED.name,
                status = AgentTraceStatus.TRUNCATED.name,
                timestampMillis = timestampMillis,
                errorCategory = AgentTraceErrorCategory.NONE.name,
                attributesJson = AgentTraceRedactor.encodeAttributes(AgentTraceAttributes()),
                createdAt = timestampMillis,
            ),
        )
    }

    private companion object {
        const val APPROVAL_TTL_MILLIS = 5 * 60 * 1000L
        const val MAX_CONFIG_JSON_BYTES = 8 * 1024
        const val MAX_ERROR_JSON_BYTES = 2 * 1024
        const val MAX_SUMMARY_JSON_BYTES = 4 * 1024
        const val MAX_TRACE_ATTRIBUTES_BYTES = 2 * 1024
        const val MAX_TRACE_EVENTS_PER_RUN = 512
        const val MAX_TRACE_EVENTS_TOTAL = 10_000
        const val TRACE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MAX_TOOL_BYTES = 4 * 1024 * 1024
    }

    private suspend fun recordRunFinished(runId: String, status: AgentRunStatus, category: String?) {
        val traceStatus = when (status) {
            AgentRunStatus.SUCCEEDED -> AgentTraceStatus.SUCCEEDED
            AgentRunStatus.BLOCKED -> AgentTraceStatus.BLOCKED
            AgentRunStatus.CANCELLED -> AgentTraceStatus.CANCELLED
            AgentRunStatus.INTERRUPTED -> AgentTraceStatus.INTERRUPTED
            else -> AgentTraceStatus.FAILED
        }
        recordTrace(
            runId,
            AgentTraceEventType.RUN_FINISHED,
            traceStatus,
            errorCategory = me.rerere.rikkahub.data.model.AgentTraceRedactor.errorCategory(category),
        )
        dao.getRun(runId)?.parentRunId?.let { parentRunId ->
            recordTrace(
                parentRunId,
                AgentTraceEventType.CHILD_RUN,
                traceStatus,
                AgentTraceAttributes(childRunIdHash = me.rerere.rikkahub.data.model.AgentTraceRedactor.hash(runId)),
                errorCategory = me.rerere.rikkahub.data.model.AgentTraceRedactor.errorCategory(category),
            )
        }
    }
}

private val SAFE_TOOL_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")
private val SAFE_TOOL_CALL_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
private val SAFE_INPUT_DIGEST = Regex("[A-Za-z0-9_-]{1,128}")
private val SAFE_ARTIFACT_ID = Regex("[A-Za-z0-9_-]{1,128}")

private fun validateToolIdentity(toolName: String, toolCallId: String, inputSha256: String) {
    require(SAFE_TOOL_NAME.matches(toolName)) { "Tool name is not a safe display identifier" }
    requireSafeToolCallId(toolCallId)
    requireSafeInputDigest(inputSha256)
}

private fun requireSafeToolCallId(toolCallId: String) {
    require(SAFE_TOOL_CALL_ID.matches(toolCallId)) { "Tool call ID is not a safe display identifier" }
}

private fun requireSafeInputDigest(inputSha256: String) {
    require(SAFE_INPUT_DIGEST.matches(inputSha256)) { "Tool input digest is invalid" }
}

private fun requireSafeArtifactId(artifactId: String) {
    require(SAFE_ARTIFACT_ID.matches(artifactId)) { "Artifact ID is invalid" }
}

private fun Set<AgentRunStatus>.runStatusNames(): List<String> = map(AgentRunStatus::name)

private fun Set<ToolExecutionStatus>.toolStatusNames(): List<String> = map(ToolExecutionStatus::name)

private val AgentStepStatus.isTerminal: Boolean
    get() = this in setOf(
        AgentStepStatus.SUCCEEDED,
        AgentStepStatus.FAILED,
        AgentStepStatus.SKIPPED,
        AgentStepStatus.CANCELLED,
    )

private fun AgentRunStatus.canTransitionTo(target: AgentRunStatus): Boolean = when (this) {
    AgentRunStatus.QUEUED -> target in setOf(
        AgentRunStatus.PREFLIGHT,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.FAILED,
        AgentRunStatus.INTERRUPTED,
        AgentRunStatus.BLOCKED,
    )
    AgentRunStatus.PREFLIGHT -> target in setOf(
        AgentRunStatus.RUNNING,
        AgentRunStatus.WAITING_APPROVAL,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.FAILED,
        AgentRunStatus.INTERRUPTED,
        AgentRunStatus.BLOCKED,
    )
    AgentRunStatus.RUNNING -> target in setOf(
        AgentRunStatus.WAITING_APPROVAL,
        AgentRunStatus.SUCCEEDED,
        AgentRunStatus.FAILED,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.INTERRUPTED,
        AgentRunStatus.BLOCKED,
    )
    AgentRunStatus.WAITING_APPROVAL -> target in setOf(
        AgentRunStatus.RUNNING,
        AgentRunStatus.CANCELLED,
        AgentRunStatus.FAILED,
        AgentRunStatus.INTERRUPTED,
        AgentRunStatus.BLOCKED,
    )
    else -> false
}

private fun ToolExecutionStatus.canTransitionTo(target: ToolExecutionStatus): Boolean = when (this) {
    ToolExecutionStatus.PENDING -> target in setOf(
        ToolExecutionStatus.WAITING_APPROVAL,
        ToolExecutionStatus.AUTHORIZED,
        ToolExecutionStatus.RUNNING,
        ToolExecutionStatus.DENIED,
        ToolExecutionStatus.FAILED,
        ToolExecutionStatus.CANCELLED,
    )
    ToolExecutionStatus.WAITING_APPROVAL -> target in setOf(
        ToolExecutionStatus.AUTHORIZED,
        ToolExecutionStatus.DENIED,
        ToolExecutionStatus.FAILED,
        ToolExecutionStatus.CANCELLED,
    )
    ToolExecutionStatus.AUTHORIZED -> target in setOf(
        ToolExecutionStatus.RUNNING,
        ToolExecutionStatus.SUCCEEDED,
        ToolExecutionStatus.WAITING_APPROVAL,
        ToolExecutionStatus.FAILED,
        ToolExecutionStatus.CANCELLED,
        ToolExecutionStatus.UNKNOWN_AFTER_INTERRUPT,
    )
    ToolExecutionStatus.RUNNING -> target in setOf(
        ToolExecutionStatus.SUCCEEDED,
        ToolExecutionStatus.FAILED,
        ToolExecutionStatus.CANCELLED,
        ToolExecutionStatus.UNKNOWN_AFTER_INTERRUPT,
    )
    else -> false
}
