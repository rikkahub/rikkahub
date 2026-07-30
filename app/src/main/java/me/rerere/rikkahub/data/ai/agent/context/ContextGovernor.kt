package me.rerere.rikkahub.data.ai.agent.context

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelCapabilityProfile
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.artifacts.ToolArtifactLimitExceeded
import me.rerere.rikkahub.data.artifacts.ToolArtifactReference
import me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope
import me.rerere.rikkahub.data.artifacts.ToolArtifactStore
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 16 * 1024
private const val DEFAULT_OUTPUT_RESERVE_TOKENS = 1024
private const val MIN_SAFETY_MARGIN_TOKENS = 256

data class GovernedToolOutput(
    val modelOutput: List<UIMessagePart>,
    val reference: ToolArtifactReference,
)

/** Conservative estimator port. Provider-specific tokenizers can replace this without changing planning policy. */
interface TokenEstimator {
    fun estimateTextTokens(text: String): Int

    fun estimateMessageTokens(message: UIMessage): Int

    fun estimateToolOutputTokens(output: List<UIMessagePart>): Int

    fun estimateMessageWithoutToolOutputTokens(message: UIMessage): Int = estimateMessageTokens(
        message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(output = emptyList()) else part
        }.filterNot { it is UIMessagePart.ToolResult }),
    )
}

/** UTF-8 bytes / 2 intentionally overestimates normal text and leaves room for provider framing. */
object ConservativeTokenEstimator : TokenEstimator {
    override fun estimateTextTokens(text: String): Int =
        (text.toByteArray(StandardCharsets.UTF_8).size + 1) / 2

    override fun estimateMessageTokens(message: UIMessage): Int = 8 + message.parts.sumOf(::estimatePartTokens)

    override fun estimateToolOutputTokens(output: List<UIMessagePart>): Int =
        if (output.isEmpty()) 0 else 2 + output.sumOf(::estimatePartTokens)

    private fun estimatePartTokens(part: UIMessagePart): Int = when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
        is UIMessagePart.Image -> estimateTextTokens(part.url)
        is UIMessagePart.Video -> estimateTextTokens(part.url)
        is UIMessagePart.Audio -> estimateTextTokens(part.url)
        is UIMessagePart.Document -> estimateTextTokens(part.url) + estimateTextTokens(part.fileName)
        is UIMessagePart.Tool -> estimateTextTokens(part.toolName) + estimateTextTokens(part.input) +
            estimateToolOutputTokens(part.output)
        is UIMessagePart.ToolCall -> estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)
        is UIMessagePart.ToolResult -> estimateTextTokens(part.toolName) + estimateTextTokens(part.content.toString())
        is UIMessagePart.Search -> 1
    }
}

/** All values are token counts and are safe to persist because they contain no request content. */
@Serializable
data class ContextBudget(
    val contextWindowTokens: Int,
    val systemTokens: Int,
    val memoryTokens: Int,
    val historyTokens: Int,
    val toolSchemaTokens: Int,
    val toolOutputTokens: Int,
    val outputReserveTokens: Int,
    val safetyMarginTokens: Int,
) {
    val inputCapacityTokens: Int
        get() = contextWindowTokens - outputReserveTokens - safetyMarginTokens
}

@Serializable
data class ContextPartitionUsage(
    val systemTokens: Int = 0,
    val memoryTokens: Int = 0,
    val historyTokens: Int = 0,
    val toolSchemaTokens: Int = 0,
    val toolOutputTokens: Int = 0,
) {
    val inputTokens: Int
        get() = systemTokens + memoryTokens + historyTokens + toolSchemaTokens + toolOutputTokens
}

@Serializable
enum class ContextPlanAction {
    ALLOW,
    ARTIFACT_TOOL_OUTPUT,
    TRIM_HISTORY,
    BLOCKED,
}

@Serializable
enum class ContextPlanCode {
    CONTEXT_BUDGET_EXCEEDED,
    OUTPUT_RESERVE_EXCEEDS_CONTEXT,
}

/** Persisted, content-free budget decision shared by context planning and the AgentRun runtime. */
@Serializable
data class ContextPlan(
    // Kept for already-written AgentRun JSON and callers from the previous artifact-only governor.
    val estimatedInputTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val reservedOutputTokens: Int? = null,
    val action: String? = null,
    val reason: String? = null,
    val budget: ContextBudget? = null,
    val usage: ContextPartitionUsage? = null,
    val actions: List<ContextPlanAction> = emptyList(),
    val errorCode: ContextPlanCode? = null,
    val retainedHistoryMessages: Int = 0,
    val droppedHistoryMessages: Int = 0,
)

data class ContextPreflightRequest(
    val messages: List<UIMessage>,
    val systemMessageIds: Set<Uuid> = emptySet(),
    val memoryMessageIds: Set<Uuid> = emptySet(),
    val toolSchemaMessageIds: Set<Uuid> = emptySet(),
    val recentUserMessageIds: Set<Uuid> = emptySet(),
    /** Ephemeral schema description for estimation only. It is never included in [ContextPlan]. */
    val toolSchemaDefinition: String = "",
    val requestedOutputTokens: Int? = null,
    /** Optional caller cap for an isolated run; it can only reduce the model's context window. */
    val maxContextWindowTokens: Int? = null,
    val capabilityProfile: ModelCapabilityProfile? = null,
    val artifactRunScope: ToolArtifactRunScope? = null,
)

data class ContextPreflightResult(
    val messages: List<UIMessage>,
    /** Full history with tool bodies replaced by durable artifact references, before any history trim. */
    val governedMessages: List<UIMessage>,
    val plan: ContextPlan,
    val blocked: Boolean,
)

/** Context budget port. It also retains the artifact boundary used by tool execution. */
interface ContextGovernor {
    fun governToolOutput(
        runScope: ToolArtifactRunScope?,
        toolExecutionId: String?,
        output: List<UIMessagePart>,
    ): GovernedToolOutput

    fun preflight(request: ContextPreflightRequest): ContextPreflightResult
}

class ArtifactContextGovernor(
    private val artifactStore: ToolArtifactStore,
    private val tokenEstimator: TokenEstimator = ConservativeTokenEstimator,
) : ContextGovernor {
    override fun governToolOutput(
        runScope: ToolArtifactRunScope?,
        toolExecutionId: String?,
        output: List<UIMessagePart>,
    ): GovernedToolOutput = governToolOutput(runScope, toolExecutionId, output, forceArtifact = false)

    override fun preflight(request: ContextPreflightRequest): ContextPreflightResult {
        val limits = resolveLimits(request)
        val initialMessages = request.messages
        val initialUsage = usageFor(initialMessages, request)
        if (limits.inputCapacityTokens < 0) {
            return blocked(
                initialMessages,
                limits,
                initialUsage,
                emptyList(),
                ContextPlanCode.OUTPUT_RESERVE_EXCEEDS_CONTEXT,
            )
        }
        val initialStaticTokens = initialUsage.systemTokens + initialUsage.memoryTokens + initialUsage.toolSchemaTokens
        val artifactThreshold = ((limits.inputCapacityTokens - initialStaticTokens).coerceAtLeast(1) / 2).coerceAtLeast(1)
        val artifactized = artifactizeLargeToolOutputs(initialMessages, request.artifactRunScope, artifactThreshold)
        val usage = usageFor(artifactized.messages, request)
        val staticTokens = usage.systemTokens + usage.memoryTokens + usage.toolSchemaTokens
        val availableHistoryTokens = limits.inputCapacityTokens - staticTokens
        val actions = mutableListOf<ContextPlanAction>()
        if (artifactized.changed) actions += ContextPlanAction.ARTIFACT_TOOL_OUTPUT

        if (availableHistoryTokens < 0) {
            return blocked(artifactized.messages, limits, usage, actions, ContextPlanCode.CONTEXT_BUDGET_EXCEEDED)
        }

        val history = artifactized.messages.filterNot { message ->
            message.id in request.systemMessageIds || message.id in request.memoryMessageIds ||
                message.id in request.toolSchemaMessageIds || message.role == MessageRole.SYSTEM
        }
        val retained = retainHistory(history, request.recentUserMessageIds, availableHistoryTokens)
            ?: return blocked(artifactized.messages, limits, usage, actions, ContextPlanCode.CONTEXT_BUDGET_EXCEEDED)
        if (retained.messages.size != history.size) actions += ContextPlanAction.TRIM_HISTORY
        if (actions.isEmpty()) actions += ContextPlanAction.ALLOW

        val retainedIds = retained.messages.mapTo(mutableSetOf()) { it.id }
        val historyIds = history.mapTo(mutableSetOf()) { it.id }
        val plannedMessages = artifactized.messages.filter { message ->
            message.id !in historyIds || message.id in retainedIds
        }
        val plannedUsage = usageFor(plannedMessages, request)
        val budget = limits.withPartitions(plannedUsage)
        val finalActions = actions.toList()
        return ContextPreflightResult(
            messages = plannedMessages,
            governedMessages = artifactized.messages,
            plan = ContextPlan(
                estimatedInputTokens = plannedUsage.inputTokens,
                contextWindowTokens = budget.contextWindowTokens,
                reservedOutputTokens = budget.outputReserveTokens,
                action = finalActions.last().name,
                reason = null,
                budget = budget,
                usage = plannedUsage,
                actions = finalActions,
                retainedHistoryMessages = retained.messages.size,
                droppedHistoryMessages = history.size - retained.messages.size,
            ),
            blocked = false,
        )
    }

    private fun resolveLimits(request: ContextPreflightRequest): ResolvedLimits {
        val profile = request.capabilityProfile
        val modelContextWindow = profile?.contextWindowTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
        val contextWindow = request.maxContextWindowTokens?.takeIf { it > 0 }
            ?.coerceAtMost(modelContextWindow)
            ?: modelContextWindow
        val requestedOutput = request.requestedOutputTokens?.takeIf { it > 0 } ?: DEFAULT_OUTPUT_RESERVE_TOKENS
        val outputReserve = profile?.maxOutputTokens?.takeIf { it > 0 }?.let { requestedOutput.coerceAtMost(it) }
            ?: requestedOutput
        val safetyMargin = maxOf(MIN_SAFETY_MARGIN_TOKENS, contextWindow / 10)
        return ResolvedLimits(contextWindow, outputReserve, safetyMargin)
    }

    private fun artifactizeLargeToolOutputs(
        messages: List<UIMessage>,
        runScope: ToolArtifactRunScope?,
        thresholdTokens: Int,
    ): ArtifactizedMessages {
        var changed = false
        val rewritten = messages.map { message ->
            val parts = message.parts.mapIndexed { index, part ->
                when (part) {
                    is UIMessagePart.Tool -> {
                        if (part.output.isEmpty()) return@mapIndexed part
                        val content = contentOf(part.output)
                        val requiresArtifact = artifactStore.limits.requiresArtifact(content) ||
                            tokenEstimator.estimateToolOutputTokens(part.output) > thresholdTokens
                        if (!requiresArtifact) return@mapIndexed part
                        val executionId = part.toolExecutionId ?: "history-${message.id}-$index"
                        val governed = governToolOutput(runScope, executionId, part.output, forceArtifact = true)
                        changed = changed || governed.modelOutput != part.output
                        part.copy(output = governed.modelOutput)
                    }
                    is UIMessagePart.ToolResult -> {
                        val output = listOf(UIMessagePart.Text(JsonInstant.encodeToString(part.content)))
                        val content = contentOf(output)
                        val requiresArtifact = artifactStore.limits.requiresArtifact(content) ||
                            tokenEstimator.estimateToolOutputTokens(output) > thresholdTokens
                        if (!requiresArtifact) return@mapIndexed part
                        val governed = governToolOutput(runScope, "history-${message.id}-$index", output, forceArtifact = true)
                        val modelText = (governed.modelOutput.singleOrNull() as? UIMessagePart.Text)?.text
                            ?: JsonInstant.encodeToString(governed.modelOutput)
                        changed = true
                        part.copy(content = JsonPrimitive(modelText))
                    }
                    else -> part
                }
            }
            if (parts == message.parts) message else message.copy(parts = parts)
        }
        return ArtifactizedMessages(rewritten, changed)
    }

    private fun retainHistory(
        history: List<UIMessage>,
        recentUserMessageIds: Set<Uuid>,
        capacityTokens: Int,
    ): RetainedHistory? {
        val protected = protectedHistoryIndexes(history, recentUserMessageIds)
        val costs = history.map { message ->
            tokenEstimator.estimateMessageWithoutToolOutputTokens(message) +
                message.toolOutputTokens()
        }
        val selected = protected.toMutableSet()
        var used = protected.sumOf { costs[it] }
        if (used > capacityTokens) return null
        for (index in history.indices.reversed()) {
            if (index !in selected && used + costs[index] <= capacityTokens) {
                selected += index
                used += costs[index]
            }
        }
        return RetainedHistory(history.filterIndexed { index, _ -> index in selected })
    }

    /** Never split a call from its result, including legacy TOOL-role messages. */
    private fun protectedHistoryIndexes(history: List<UIMessage>, recentUserMessageIds: Set<Uuid>): Set<Int> {
        val protected = history.indices.filterTo(mutableSetOf()) { history[it].id in recentUserMessageIds }
        history.indices.filter { history[it].hasToolData() }.forEach { index ->
            protected += index
            if (history[index].role == MessageRole.TOOL) {
                (index - 1).takeIf { it >= 0 && history[it].hasToolData() }?.let(protected::add)
            }
            if (history[index].role == MessageRole.ASSISTANT) {
                (index + 1).takeIf { it < history.size && history[it].role == MessageRole.TOOL }?.let(protected::add)
            }
        }
        return protected
    }

    private fun usageFor(messages: List<UIMessage>, request: ContextPreflightRequest): ContextPartitionUsage {
        var system = 0
        var memory = 0
        var history = 0
        // The provider schema is represented by toolSchemaDefinition. Do not charge the matching
        // system helper message a second time.
        var toolSchema = tokenEstimator.estimateTextTokens(request.toolSchemaDefinition)
        var toolOutput = 0
        messages.forEach { message ->
            val messageTokens = tokenEstimator.estimateMessageWithoutToolOutputTokens(message)
            when {
                message.id in request.memoryMessageIds -> memory += messageTokens
                message.id in request.toolSchemaMessageIds -> Unit
                message.id in request.systemMessageIds || message.role == MessageRole.SYSTEM -> system += messageTokens
                else -> history += messageTokens
            }
            if (message.role != MessageRole.SYSTEM) {
                toolOutput += message.toolOutputTokens()
            }
        }
        return ContextPartitionUsage(system, memory, history, toolSchema, toolOutput)
    }

    private fun blocked(
        messages: List<UIMessage>,
        limits: ResolvedLimits,
        usage: ContextPartitionUsage,
        actions: List<ContextPlanAction>,
        code: ContextPlanCode,
    ): ContextPreflightResult {
        val budget = limits.withPartitions(usage)
        val finalActions = (actions + ContextPlanAction.BLOCKED).distinct()
        return ContextPreflightResult(
            messages = messages,
            governedMessages = messages,
            plan = ContextPlan(
                estimatedInputTokens = usage.inputTokens,
                contextWindowTokens = budget.contextWindowTokens,
                reservedOutputTokens = budget.outputReserveTokens,
                action = ContextPlanAction.BLOCKED.name,
                reason = code.name,
                budget = budget,
                usage = usage,
                actions = finalActions,
                errorCode = code,
            ),
            blocked = true,
        )
    }

    private fun governToolOutput(
        runScope: ToolArtifactRunScope?,
        toolExecutionId: String?,
        output: List<UIMessagePart>,
        forceArtifact: Boolean,
    ): GovernedToolOutput {
        val content = contentOf(output)
        val mimeType = if (output.all { it is UIMessagePart.Text }) "text/plain; charset=utf-8" else "application/json"
        val reference = ToolArtifactReference(
            artifactId = null,
            toolExecutionId = toolExecutionId,
            sha256 = content.digest(),
            sizeBytes = content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            mimeType = mimeType,
            preview = fixedPreview(content, artifactStore.limits.previewMaxBytes),
            stored = false,
        )
        if (!forceArtifact && !artifactStore.limits.requiresArtifact(content)) return GovernedToolOutput(output, reference)

        val scope = runScope?.takeIf { toolExecutionId != null }?.forExecution(toolExecutionId!!)
            ?: return rejected(reference, "Tool output could not be retained because its run scope is unavailable.")
        val stored = try {
            artifactStore.create(scope, content, mimeType).copy(preview = reference.preview)
        } catch (_: ToolArtifactLimitExceeded) {
            return rejected(reference, "Tool output exceeded the private artifact retention limit.")
        }
        return GovernedToolOutput(
            modelOutput = listOf(UIMessagePart.Text(stored.referenceText())),
            reference = stored,
        )
    }

    private fun rejected(reference: ToolArtifactReference, message: String): GovernedToolOutput = GovernedToolOutput(
        modelOutput = listOf(UIMessagePart.Text("[Tool output unavailable] $message")),
        reference = reference,
    )

    private fun ToolArtifactReference.referenceText(): String = buildString {
        appendLine("[Tool output stored as private artifact]")
        appendLine("artifactId: $artifactId")
        appendLine("toolExecutionId: $toolExecutionId")
        appendLine("sha256: $sha256")
        appendLine("size: $sizeBytes")
        appendLine("mime: $mimeType")
        appendLine("preview:")
        append(preview)
    }

    private fun contentOf(output: List<UIMessagePart>): String = if (output.all { it is UIMessagePart.Text }) {
        output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    } else {
        JsonInstant.encodeToString(output)
    }

    private data class ArtifactizedMessages(val messages: List<UIMessage>, val changed: Boolean)

    private fun UIMessage.toolOutputTokens(): Int = getTools().sumOf {
        tokenEstimator.estimateToolOutputTokens(it.output)
    } + parts.filterIsInstance<UIMessagePart.ToolResult>().sumOf { result ->
        tokenEstimator.estimateToolOutputTokens(listOf(UIMessagePart.Text(JsonInstant.encodeToString(result.content))))
    }

    private data class RetainedHistory(val messages: List<UIMessage>)

    private data class ResolvedLimits(
        val contextWindowTokens: Int,
        val outputReserveTokens: Int,
        val safetyMarginTokens: Int,
    ) {
        val inputCapacityTokens: Int
            get() = contextWindowTokens - outputReserveTokens - safetyMarginTokens

        fun withPartitions(usage: ContextPartitionUsage): ContextBudget {
            val flexibleTokens = (inputCapacityTokens - usage.systemTokens - usage.memoryTokens - usage.toolSchemaTokens)
                .coerceAtLeast(0)
            val toolOutputBudget = minOf(usage.toolOutputTokens, flexibleTokens)
            return ContextBudget(
                contextWindowTokens = contextWindowTokens,
                systemTokens = usage.systemTokens,
                memoryTokens = usage.memoryTokens,
                historyTokens = flexibleTokens - toolOutputBudget,
                toolSchemaTokens = usage.toolSchemaTokens,
                toolOutputTokens = toolOutputBudget,
                outputReserveTokens = outputReserveTokens,
                safetyMarginTokens = safetyMarginTokens,
            )
        }
    }
}

private fun UIMessage.hasToolData(): Boolean = parts.any {
    it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult
}

private fun fixedPreview(content: String, maxBytes: Int): String {
    val redacted = content.replace(HOST_PATH, "[REDACTED_HOST_PATH]")
    val result = StringBuilder()
    var index = 0
    while (index < redacted.length) {
        val codePoint = redacted.codePointAt(index)
        val value = String(Character.toChars(codePoint))
        if (
            result.toString().toByteArray(StandardCharsets.UTF_8).size +
                value.toByteArray(StandardCharsets.UTF_8).size > maxBytes
        ) break
        result.append(value)
        index += Character.charCount(codePoint)
    }
    return result.toString()
}

private fun String.digest(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val HOST_PATH = Regex("(?:file:/{2,3}|[A-Za-z]:[\\\\/]|/)(?:[^\\s\"'`,}\\]]+)")
