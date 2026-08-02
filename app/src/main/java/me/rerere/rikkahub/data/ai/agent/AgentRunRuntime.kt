package me.rerere.rikkahub.data.ai.agent

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.permission.PolicyDecision
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptor
import me.rerere.rikkahub.data.ai.agent.context.ContextPlan
import me.rerere.rikkahub.data.model.AgentApprovalSummary
import me.rerere.rikkahub.data.model.AgentApprovalStatus
import me.rerere.rikkahub.data.model.ApprovalResolution
import me.rerere.rikkahub.data.model.AgentRunError
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentRunSummary
import me.rerere.rikkahub.data.model.AgentStepStatus
import me.rerere.rikkahub.data.model.AgentStepSummary
import me.rerere.rikkahub.data.model.AgentTraceAttributes
import me.rerere.rikkahub.data.model.AgentTraceErrorCategory
import me.rerere.rikkahub.data.model.AgentTraceEventType
import me.rerere.rikkahub.data.model.AgentTraceRedactor
import me.rerere.rikkahub.data.model.AgentTraceStatus
import me.rerere.rikkahub.data.model.ToolExecutionStatus
import me.rerere.rikkahub.data.model.ToolExecutionSummary
import me.rerere.rikkahub.data.repository.AgentRunRepository
import me.rerere.rikkahub.data.artifacts.ToolArtifactReference
import me.rerere.rikkahub.utils.JsonInstant

/** Persists redacted runtime telemetry without becoming part of message execution semantics. */
interface AgentRunRuntime {
    suspend fun stepStarted(index: Int): String?
    suspend fun stepFinished(stepId: String?, status: AgentStepStatus)
    suspend fun toolObserved(stepId: String?, tool: UIMessagePart.Tool, descriptor: ToolDescriptor): String?
    suspend fun approvalRequested(
        executionId: String?,
        tool: UIMessagePart.Tool,
        decision: PolicyDecision,
        binding: AgentApprovalSummary,
    ): String?
    suspend fun approvalResolved(approvalId: String, executionId: String, approved: Boolean): Boolean
    suspend fun approvalResolution(approvalId: String, executionId: String, approved: Boolean): ApprovalResolution
    suspend fun approvedFor(executionId: String?, tool: UIMessagePart.Tool, binding: AgentApprovalSummary): Boolean
    suspend fun toolStarted(executionId: String?): Boolean
    suspend fun toolFinished(
        executionId: String?,
        status: ToolExecutionStatus,
        output: List<UIMessagePart> = emptyList(),
        error: String? = null,
        artifact: ToolArtifactReference? = null,
    ): Boolean
    suspend fun contextPlanned(plan: ContextPlan)
    suspend fun contextBlocked(plan: ContextPlan)
    suspend fun modelCallStarted(stepId: String?)
    suspend fun modelCallFinished(
        stepId: String?,
        succeeded: Boolean,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
    )
    suspend fun policyDecision(tool: UIMessagePart.Tool, decision: PolicyDecision)
    suspend fun waitingForApproval()
    suspend fun finished(reason: String)
    suspend fun failed(error: Throwable)
    suspend fun cancelled()
}

object NoOpAgentRunRuntime : AgentRunRuntime {
    override suspend fun stepStarted(index: Int) = null
    override suspend fun stepFinished(stepId: String?, status: AgentStepStatus) = Unit
    override suspend fun toolObserved(stepId: String?, tool: UIMessagePart.Tool, descriptor: ToolDescriptor) = null
    override suspend fun approvalRequested(executionId: String?, tool: UIMessagePart.Tool, decision: PolicyDecision, binding: AgentApprovalSummary) = executionId ?: "noop-approval"
    override suspend fun approvalResolved(approvalId: String, executionId: String, approved: Boolean) = false
    override suspend fun approvalResolution(approvalId: String, executionId: String, approved: Boolean) = ApprovalResolution()
    // A non-persisted runtime has no durable approval binding, so it must never resume an approved tool.
    override suspend fun approvedFor(executionId: String?, tool: UIMessagePart.Tool, binding: AgentApprovalSummary) = false
    override suspend fun toolStarted(executionId: String?) = true
    override suspend fun toolFinished(
        executionId: String?,
        status: ToolExecutionStatus,
        output: List<UIMessagePart>,
        error: String?,
        artifact: ToolArtifactReference?,
    ) = true
    override suspend fun contextPlanned(plan: ContextPlan) = Unit
    override suspend fun contextBlocked(plan: ContextPlan) = Unit
    override suspend fun modelCallStarted(stepId: String?) = Unit
    override suspend fun modelCallFinished(
        stepId: String?,
        succeeded: Boolean,
        inputTokens: Int?,
        outputTokens: Int?,
    ) = Unit
    override suspend fun policyDecision(tool: UIMessagePart.Tool, decision: PolicyDecision) = Unit
    override suspend fun waitingForApproval() = Unit
    override suspend fun finished(reason: String) = Unit
    override suspend fun failed(error: Throwable) = Unit
    override suspend fun cancelled() = Unit
}

/**
 * Persists only lifecycle state and the minimum durable binding required for a user approval.
 * Normal steps and automatically executed tools intentionally remain in memory.
 */
class MinimalAgentRunRuntime(
    private val repository: AgentRunRepository,
    private val runId: String,
) : AgentRunRuntime {
    private data class ObservedTool(
        val stepId: String,
        val toolName: String,
        val toolCallId: String,
        val inputSha256: String,
    )

    private val persisted = PersistedAgentRunRuntime(repository, runId)
    private val observedTools = ConcurrentHashMap<String, ObservedTool>()
    private val executionByStepAndCall = ConcurrentHashMap<Pair<String, String>, String>()
    private val persistedStepIds = ConcurrentHashMap.newKeySet<String>()

    override suspend fun stepStarted(index: Int): String = Uuid.random().toString()

    override suspend fun stepFinished(stepId: String?, status: AgentStepStatus) {
        if (stepId == null) return
        if (stepId in persistedStepIds || repository.getSteps(runId).any { it.id == stepId }) {
            persisted.stepFinished(stepId, status)
        }
        observedTools.entries.removeAll { (_, observed) -> observed.stepId == stepId }
        executionByStepAndCall.keys.removeAll { (observedStepId, _) -> observedStepId == stepId }
    }

    override suspend fun toolObserved(
        stepId: String?,
        tool: UIMessagePart.Tool,
        descriptor: ToolDescriptor,
    ): String? {
        val resolvedStepId = stepId ?: return null
        val digest = tool.input.canonicalJson().digest()
        repository.getLatestToolExecutionByCall(runId, tool.toolName, tool.toolCallId, digest)
            ?.let { return it.id }
        val callKey = resolvedStepId to tool.toolCallId
        executionByStepAndCall[callKey]?.let { existingId ->
            val existing = observedTools.getValue(existingId)
            require(existing.toolName == tool.toolName && existing.inputSha256 == digest) {
                "Duplicate toolCallId '${tool.toolCallId}' in the same model turn"
            }
            return existingId
        }
        val executionId = Uuid.random().toString()
        observedTools[executionId] = ObservedTool(
            stepId = resolvedStepId,
            toolName = tool.toolName,
            toolCallId = tool.toolCallId,
            inputSha256 = digest,
        )
        executionByStepAndCall[callKey] = executionId
        return executionId
    }

    override suspend fun approvalRequested(
        executionId: String?,
        tool: UIMessagePart.Tool,
        decision: PolicyDecision,
        binding: AgentApprovalSummary,
    ): String? {
        val resolvedExecutionId = executionId ?: return null
        if (repository.getToolExecution(resolvedExecutionId) == null) {
            val observed = observedTools[resolvedExecutionId] ?: return null
            if (
                observed.toolName != tool.toolName ||
                observed.toolCallId != tool.toolCallId ||
                observed.inputSha256 != tool.input.canonicalJson().digest()
            ) return null
            if (repository.getSteps(runId).none { it.id == observed.stepId }) {
                repository.recordStep(
                    id = observed.stepId,
                    runId = runId,
                    kind = "approval",
                    status = AgentStepStatus.RUNNING,
                    summary = null,
                )
            }
            persistedStepIds += observed.stepId
            repository.recordToolExecution(
                id = resolvedExecutionId,
                runId = runId,
                stepId = observed.stepId,
                toolName = observed.toolName,
                toolCallId = observed.toolCallId,
                inputSha256 = observed.inputSha256,
                summary = ToolExecutionSummary(
                    toolCallId = observed.toolCallId,
                    inputSha256 = observed.inputSha256,
                ),
            )
        }
        return persisted.approvalRequested(resolvedExecutionId, tool, decision, binding)
    }

    override suspend fun approvalResolved(
        approvalId: String,
        executionId: String,
        approved: Boolean,
    ): Boolean = persisted.approvalResolved(approvalId, executionId, approved)

    override suspend fun approvalResolution(
        approvalId: String,
        executionId: String,
        approved: Boolean,
    ): ApprovalResolution = persisted.approvalResolution(approvalId, executionId, approved)

    override suspend fun approvedFor(
        executionId: String?,
        tool: UIMessagePart.Tool,
        binding: AgentApprovalSummary,
    ): Boolean = persisted.approvedFor(executionId, tool, binding)

    override suspend fun toolStarted(executionId: String?): Boolean =
        if (executionId != null && repository.getToolExecution(executionId) != null) {
            persisted.toolStarted(executionId)
        } else {
            true
        }

    override suspend fun toolFinished(
        executionId: String?,
        status: ToolExecutionStatus,
        output: List<UIMessagePart>,
        error: String?,
        artifact: ToolArtifactReference?,
    ): Boolean {
        val resolvedExecutionId = executionId ?: return false
        val execution = repository.getToolExecution(resolvedExecutionId)
        if (execution == null) {
            observedTools.remove(resolvedExecutionId)?.let { observed ->
                executionByStepAndCall.remove(observed.stepId to observed.toolCallId)
            }
            return true
        }
        val summary = execution.summaryJson?.let {
            runCatching { JsonInstant.decodeFromString<ToolExecutionSummary>(it) }.getOrNull()
        }
        return repository.transitionToolExecution(
            resolvedExecutionId,
            toolFinishSourceStatuses(status),
            status,
            error?.let { AgentRunError(it, category = "tool") },
            summary,
        )
    }

    override suspend fun contextPlanned(plan: ContextPlan) = Unit

    override suspend fun contextBlocked(plan: ContextPlan) {
        repository.blockRun(
            runId,
            plan.errorCode?.name ?: "CONTEXT_BUDGET_EXCEEDED",
            "context_budget",
        )
    }

    override suspend fun modelCallStarted(stepId: String?) = Unit

    override suspend fun modelCallFinished(
        stepId: String?,
        succeeded: Boolean,
        inputTokens: Int?,
        outputTokens: Int?,
    ) = Unit

    override suspend fun policyDecision(tool: UIMessagePart.Tool, decision: PolicyDecision) = Unit

    override suspend fun waitingForApproval() {
        repository.transitionRun(runId, setOf(AgentRunStatus.RUNNING), AgentRunStatus.WAITING_APPROVAL)
    }

    override suspend fun finished(reason: String) {
        if (reason == "max_steps") {
            repository.failRun(runId, "MAX_STEPS", "runtime")
        } else {
            repository.transitionRun(
                runId,
                setOf(AgentRunStatus.RUNNING),
                AgentRunStatus.SUCCEEDED,
                summary = AgentRunSummary(outcome = reason),
            )
        }
    }

    override suspend fun failed(error: Throwable) {
        repository.failRun(runId, error.javaClass.simpleName, "runtime")
    }

    override suspend fun cancelled() {
        repository.cancelRun(runId)
    }
}

internal fun toolFinishSourceStatuses(status: ToolExecutionStatus): Set<ToolExecutionStatus> = when (status) {
    ToolExecutionStatus.SUCCEEDED,
    ToolExecutionStatus.UNKNOWN_AFTER_INTERRUPT,
    -> setOf(ToolExecutionStatus.AUTHORIZED, ToolExecutionStatus.RUNNING)

    ToolExecutionStatus.DENIED -> setOf(ToolExecutionStatus.PENDING)
    ToolExecutionStatus.FAILED,
    ToolExecutionStatus.CANCELLED,
    -> setOf(ToolExecutionStatus.PENDING, ToolExecutionStatus.AUTHORIZED, ToolExecutionStatus.RUNNING)

    ToolExecutionStatus.PENDING,
    ToolExecutionStatus.WAITING_APPROVAL,
    ToolExecutionStatus.AUTHORIZED,
    ToolExecutionStatus.RUNNING,
    -> throw IllegalArgumentException("Tool finish status must be terminal: $status")
}

class PersistedAgentRunRuntime(
    private val repository: AgentRunRepository,
    private val runId: String,
) : AgentRunRuntime {
    private var contextPlan: ContextPlan? = null
    private val modelCallStartedAt = mutableMapOf<String?, Long>()
    override suspend fun stepStarted(index: Int): String {
        val id = Uuid.random().toString()
        repository.recordStep(id, runId, "agent", AgentStepStatus.RUNNING, AgentStepSummary("agent"))
        return id
    }

    override suspend fun stepFinished(stepId: String?, status: AgentStepStatus) {
        if (stepId != null) {
            repository.transitionStep(stepId, setOf(AgentStepStatus.RUNNING), status)
            safeTrace(
                AgentTraceEventType.CHECKPOINT,
                if (status == AgentStepStatus.SUCCEEDED) AgentTraceStatus.FINISHED else AgentTraceStatus.FAILED,
            )
        }
    }

    override suspend fun toolObserved(stepId: String?, tool: UIMessagePart.Tool, descriptor: ToolDescriptor): String? {
        val resolvedStepId = stepId ?: return null
        val canonicalInput = tool.input.canonicalJson()
        val digest = canonicalInput.digest()
        // An AgentLoop observes a freshly generated call before it executes it; make that repeat idempotent.
        repository.getToolExecutionByIdentity(runId, resolvedStepId, tool.toolName, tool.toolCallId, digest)
            ?.let { return it.id }
        require(!repository.hasToolCallInStep(runId, resolvedStepId, tool.toolCallId)) {
            "Duplicate toolCallId '${tool.toolCallId}' in the same model turn"
        }
        repository.getLatestToolExecutionByCall(runId, tool.toolName, tool.toolCallId, digest)?.let { return it.id }
        val summary = ToolExecutionSummary(
            category = descriptor.category.name,
            operation = descriptor.capability.name,
            targetType = descriptor.sideEffect.name,
            toolCallId = tool.toolCallId,
            inputSha256 = digest,
            inputBytes = canonicalInput.toByteArray().size,
        )
        return repository.recordToolExecution(
            id = Uuid.random().toString(), runId = runId, stepId = resolvedStepId,
            toolName = tool.toolName, toolCallId = tool.toolCallId, inputSha256 = digest, summary = summary,
        ).id
    }

    override suspend fun approvalRequested(
        executionId: String?,
        tool: UIMessagePart.Tool,
        decision: PolicyDecision,
        binding: AgentApprovalSummary,
    ): String? {
        if (executionId == null) return null
        val execution = repository.getToolExecution(executionId) ?: return null
        if (
            execution.toolName != tool.toolName ||
            execution.toolCallId != tool.toolCallId ||
            execution.inputSha256 != tool.input.canonicalJson().digest()
        ) return null
        repository.getApprovalForExecution(executionId, AgentApprovalStatus.PENDING)?.let { return it.id }
        val approvalId = Uuid.random().toString()
        return approvalId.takeIf { repository.requestApproval(
            id = approvalId, runId = runId, toolExecutionId = executionId,
            summary = binding.copy(
                policySource = decision::class.simpleName,
                reasonCode = decision.code.name,
                // The approval is always bound to the original execution, not the continuation step.
                stepId = execution.stepId,
                toolName = execution.toolName,
                toolCallId = execution.toolCallId,
                inputSha256 = execution.inputSha256,
            ),
        ) }?.also {
            safeTrace(AgentTraceEventType.APPROVAL, AgentTraceStatus.ASK)
        }
    }

    override suspend fun approvalResolved(approvalId: String, executionId: String, approved: Boolean): Boolean {
        return repository.resolveApproval(approvalId, executionId, approved).also { resolved ->
            if (resolved) safeTrace(AgentTraceEventType.APPROVAL, if (approved) AgentTraceStatus.APPROVED else AgentTraceStatus.REJECTED)
        }
    }

    override suspend fun approvalResolution(
        approvalId: String,
        executionId: String,
        approved: Boolean,
    ): ApprovalResolution {
        val approval = repository.getApproval(approvalId) ?: return ApprovalResolution()
        if (approval.toolExecutionId != executionId) return ApprovalResolution()
        return repository.resolveApprovalWithReplacement(approvalId, approved).also { resolution ->
            if (resolution.resolved) safeTrace(AgentTraceEventType.APPROVAL, if (approved) AgentTraceStatus.APPROVED else AgentTraceStatus.REJECTED)
        }
    }

    override suspend fun approvedFor(executionId: String?, tool: UIMessagePart.Tool, binding: AgentApprovalSummary): Boolean {
        return executionId != null && repository.authorizationFor(runId, executionId, tool, binding)
    }

    override suspend fun toolStarted(executionId: String?): Boolean {
        val started = executionId == null || repository.transitionToolExecution(
                executionId,
                setOf(ToolExecutionStatus.PENDING, ToolExecutionStatus.AUTHORIZED),
                ToolExecutionStatus.RUNNING,
            )
        if (started && executionId != null) {
            val execution = repository.getToolExecution(executionId)
            safeTrace(
                AgentTraceEventType.TOOL_STARTED,
                AgentTraceStatus.STARTED,
                AgentTraceAttributes(
                    toolNameHash = AgentTraceRedactor.hash(execution?.toolName),
                    toolExecutionIdHash = AgentTraceRedactor.hash(executionId),
                ),
            )
        }
        return started
    }

    override suspend fun toolFinished(
        executionId: String?,
        status: ToolExecutionStatus,
        output: List<UIMessagePart>,
        error: String?,
        artifact: ToolArtifactReference?,
    ): Boolean {
        if (executionId == null) return false
        val text = JsonInstant.encodeToString(output)
        val existing = repository.getToolExecution(executionId)
        val summary = existing?.summaryJson?.let {
            runCatching { JsonInstant.decodeFromString<ToolExecutionSummary>(it) }.getOrNull()
        } ?: ToolExecutionSummary()
        val transitioned = repository.transitionToolExecution(
            executionId,
            toolFinishSourceStatuses(status),
            status,
            error?.let { AgentRunError(it, category = "tool") },
            summary.copy(
                outputSha256 = artifact?.sha256 ?: text.digest(),
                outputBytes = (artifact?.sizeBytes ?: text.toByteArray().size.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                // Artifact previews remain in private storage and must not enter AgentRun telemetry.
                outputPreview = null,
                outputMimeType = artifact?.mimeType,
                outputArtifactId = artifact?.artifactId,
            ),
        )
        if (!transitioned) return false
        safeTrace(
            AgentTraceEventType.TOOL_FINISHED,
            when (status) {
                ToolExecutionStatus.SUCCEEDED -> AgentTraceStatus.SUCCEEDED
                ToolExecutionStatus.DENIED -> AgentTraceStatus.DENIED
                ToolExecutionStatus.CANCELLED -> AgentTraceStatus.CANCELLED
                else -> AgentTraceStatus.FAILED
            },
            AgentTraceAttributes(
                toolNameHash = AgentTraceRedactor.hash(existing?.toolName),
                toolExecutionIdHash = AgentTraceRedactor.hash(executionId),
                outputSha256 = artifact?.sha256 ?: text.digest(),
                artifactSha256 = artifact?.sha256,
                byteCount = artifact?.sizeBytes ?: text.toByteArray().size.toLong(),
            ),
            when (status) {
                ToolExecutionStatus.SUCCEEDED -> AgentTraceErrorCategory.NONE
                ToolExecutionStatus.DENIED -> AgentTraceErrorCategory.POLICY
                else -> AgentTraceErrorCategory.TOOL
            },
        )
        return true
    }

    override suspend fun contextPlanned(plan: ContextPlan) {
        contextPlan = plan
        repository.recordStep(
            id = Uuid.random().toString(),
            runId = runId,
            kind = "context_preflight",
            status = AgentStepStatus.SUCCEEDED,
            summary = AgentStepSummary("context_preflight", plan.telemetryDetail()),
        )
        safeTrace(
            AgentTraceEventType.CONTEXT_PLANNED,
            AgentTraceStatus.ALLOWED,
            plan.traceAttributes(),
        )
    }

    override suspend fun contextBlocked(plan: ContextPlan) {
        contextPlan = plan
        repository.recordStep(
            id = Uuid.random().toString(),
            runId = runId,
            kind = "context_preflight",
            status = AgentStepStatus.FAILED,
            summary = AgentStepSummary("context_preflight", plan.telemetryDetail()),
        )
        safeTrace(
            AgentTraceEventType.CONTEXT_PLANNED,
            AgentTraceStatus.BLOCKED,
            plan.traceAttributes(),
            AgentTraceErrorCategory.CONTEXT_BUDGET,
        )
        repository.blockRun(
            runId = runId,
            error = AgentRunError(
                plan.errorCode?.name ?: "CONTEXT_BUDGET_EXCEEDED",
                category = "context_budget",
                retryable = false,
            ),
            summary = AgentRunSummary(outcome = "context_budget_exceeded", contextPlan = plan),
        )
    }

    override suspend fun modelCallStarted(stepId: String?) {
        modelCallStartedAt[stepId] = System.currentTimeMillis()
        safeTrace(AgentTraceEventType.MODEL_CALL_STARTED, AgentTraceStatus.STARTED)
    }

    override suspend fun modelCallFinished(
        stepId: String?,
        succeeded: Boolean,
        inputTokens: Int?,
        outputTokens: Int?,
    ) {
        val startedAt = modelCallStartedAt.remove(stepId)
        safeTrace(
            AgentTraceEventType.MODEL_CALL_FINISHED,
            if (succeeded) AgentTraceStatus.SUCCEEDED else AgentTraceStatus.FAILED,
            AgentTraceAttributes(inputTokens = inputTokens, outputTokens = outputTokens, queuePeak = 1),
            durationMillis = startedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
            errorCategory = if (succeeded) AgentTraceErrorCategory.NONE else AgentTraceErrorCategory.PROVIDER,
        )
    }

    override suspend fun policyDecision(tool: UIMessagePart.Tool, decision: PolicyDecision) {
        val status = when (decision) {
            is PolicyDecision.Allow -> AgentTraceStatus.ALLOWED
            is PolicyDecision.Ask -> AgentTraceStatus.ASK
            is PolicyDecision.Deny -> AgentTraceStatus.DENIED
        }
        safeTrace(
            AgentTraceEventType.POLICY_DECISION,
            status,
            AgentTraceAttributes(
                toolNameHash = AgentTraceRedactor.hash(tool.toolName),
                policyCodeHash = AgentTraceRedactor.hash(decision.code.name),
            ),
            if (decision is PolicyDecision.Deny) AgentTraceErrorCategory.POLICY else AgentTraceErrorCategory.NONE,
        )
    }

    override suspend fun waitingForApproval() {
        repository.transitionRun(runId, setOf(AgentRunStatus.RUNNING), AgentRunStatus.WAITING_APPROVAL)
    }

    override suspend fun finished(reason: String) {
        if (reason == "max_steps") {
            repository.failRun(runId, "MAX_STEPS", "runtime")
            return
        }
        repository.transitionRun(
            runId, setOf(AgentRunStatus.RUNNING), AgentRunStatus.SUCCEEDED,
            error = null,
            summary = AgentRunSummary(outcome = reason, contextPlan = contextPlan),
        )
    }

    override suspend fun failed(error: Throwable) {
        repository.failRun(runId, error.javaClass.simpleName, "runtime")
    }

    override suspend fun cancelled() {
        repository.cancelRun(runId)
    }

    private suspend fun safeTrace(
        type: AgentTraceEventType,
        status: AgentTraceStatus,
        attributes: AgentTraceAttributes = AgentTraceAttributes(),
        errorCategory: AgentTraceErrorCategory = AgentTraceErrorCategory.NONE,
        durationMillis: Long? = null,
    ) {
        try {
            repository.recordTrace(runId, type, status, attributes, errorCategory, durationMillis = durationMillis)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

}

private fun ContextPlan.traceAttributes() = AgentTraceAttributes(
    contextInputTokens = estimatedInputTokens,
    contextWindowTokens = contextWindowTokens,
    outputReserveTokens = reservedOutputTokens,
    inputTokens = usage?.inputTokens,
)

private fun ContextPlan.telemetryDetail(): String = listOfNotNull(
    "input=${estimatedInputTokens}",
    "window=${contextWindowTokens}",
    "reserve=${reservedOutputTokens}",
    usage?.let {
        "partitions=system:${it.systemTokens},memory:${it.memoryTokens},history:${it.historyTokens}," +
            "tool_schema:${it.toolSchemaTokens},tool_output:${it.toolOutputTokens}"
    },
    "actions=${actions.joinToString(",") { it.name }}",
    errorCode?.let { "error=$it" },
).joinToString(";")

fun String.digest(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { "%02x".format(it) }

/** Reject malformed input and make semantically equal JSON map to the same approval identity. */
fun String.canonicalJson(): String {
    val element = JsonInstant.parseToJsonElement(this)
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        is JsonPrimitive -> value
    }
    return JsonInstant.encodeToString(canonical(element))
}
