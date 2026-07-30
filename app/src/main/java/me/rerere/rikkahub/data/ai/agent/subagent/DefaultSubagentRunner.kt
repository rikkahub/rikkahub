package me.rerere.rikkahub.data.ai.agent.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.effectiveCapabilityProfile
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.agent.AgentLoop
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.AgentRunRuntime
import me.rerere.rikkahub.data.ai.agent.PersistedAgentRunRuntime
import me.rerere.rikkahub.data.ai.agent.canonicalJson
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.preflight.ProviderPreflight
import me.rerere.rikkahub.data.ai.agent.preflight.ProviderPreflightAction
import me.rerere.rikkahub.data.ai.agent.preflight.ProviderPreflightRequest
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AgentRunConfigSnapshot
import me.rerere.rikkahub.data.model.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentRunSummary
import me.rerere.rikkahub.data.model.AgentStepStatus
import me.rerere.rikkahub.data.model.AgentStepSummary
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.model.ChildRunReport
import me.rerere.rikkahub.data.model.toSnapshotSummary
import me.rerere.rikkahub.data.repository.AgentRunRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "DefaultSubagentRunner"

/**
 * 真正运行隔离 Explore 会话：独立消息列表 + PLAN 模式 + 只读工具白名单 + AgentLoop。
 */
class DefaultSubagentRunner(
    private val agentLoop: AgentLoop,
    private val toolRegistry: ToolRegistry,
    private val agentRunRepository: AgentRunRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val limits: ControlledSubagentLimits = ControlledSubagentLimits(),
) : SubagentRunner {

    override suspend fun run(request: SubagentRequest): SubagentResult {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return SubagentResult(
                report = ChildRunReport(unresolved = listOf("TASK_REQUIRED")),
            )
        }

        val parentRunId = request.parentRunId ?: return rejected("PARENT_RUN_REQUIRED")
        val parent = agentRunRepository.getRun(parentRunId) ?: return rejected("PARENT_RUN_NOT_FOUND")
        if (parent.parentRunId != null) return rejected("NESTED_CHILD_RUN_FORBIDDEN")
        if (parent.status !in AgentRunStatus.ACTIVE.map(AgentRunStatus::name)) return rejected("PARENT_RUN_NOT_ACTIVE")
        if (parent.conversationId != request.conversation.id.toString() || parent.assistantId != request.assistant.id.toString()) {
            return rejected("PARENT_SCOPE_MISMATCH")
        }

        val settings = request.settings
        val model = settings.findModelById(
            request.assistant.chatModelId ?: settings.chatModelId
        ) ?: return SubagentResult(
            report = ChildRunReport(unresolved = listOf("CHAT_MODEL_NOT_FOUND")),
        )

        val maxSteps = request.spec.maxSteps.coerceIn(1, SubagentSpec.HARD_MAX_STEPS)
        val budget = request.spec.budget
        val exploreAssistant = buildExploreAssistant(request.assistant, request.spec, budget)
        val childRunId = Uuid.random().toString()
        val childConfig = AgentRunConfigSnapshot(
            runtimeVersion = "controlled-explore-v1",
            conversationId = request.conversation.id.toString(),
            assistantId = request.assistant.id.toString(),
            modelId = model.id.toString(),
            agentMode = AgentMode.PLAN.name,
            maxSteps = maxSteps,
            toolPolicyVersion = "controlled-explore-v1",
            toolDescriptors = ExploreToolAllowlist.DEFAULT.sorted(),
            childBudget = budget.snapshot(maxSteps),
            capabilitySummary = model.effectiveCapabilityProfile().toSnapshotSummary(),
        )
        try {
            agentRunRepository.createControlledChildRun(
                id = childRunId,
                parentRunId = parentRunId,
                conversationId = request.conversation.id.toString(),
                assistantId = request.assistant.id.toString(),
                configSnapshot = childConfig,
                maxChildren = limits.maxChildrenPerParent,
                maxTotalTokens = limits.maxTotalTokensPerParent,
                maxTotalDurationMillis = limits.maxTotalDurationMillisPerParent,
                maxConcurrentChildren = limits.maxConcurrentChildren,
            )
            agentRunRepository.transitionRun(childRunId, setOf(AgentRunStatus.QUEUED), AgentRunStatus.PREFLIGHT)
        } catch (error: IllegalArgumentException) {
            return rejected(error.message ?: "CHILD_RUN_REJECTED")
        }

        val resolveCtx = ToolResolveContext(
            settings = settings,
            assistant = exploreAssistant,
            conversation = request.conversation,
            mode = request.spec.mode,
            permissionPolicy = PermissionPolicy.compatibleDefault(injectPromptForWorkspace = true),
            isSubagentRun = true,
            agentRunId = childRunId,
            authorizedParentArtifactRunId = parentRunId,
        )

        val tools = try {
            toolRegistry.resolve(resolveCtx)
                .filter { tool ->
                    val allowedByDefault = ExploreToolAllowlist.isAllowed(tool.name)
                    val allowedBySpec = request.spec.allowedToolNames
                        ?.let { tool.name in it }
                        ?: true
                    allowedByDefault && allowedBySpec
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve explore tools", e)
            agentRunRepository.failRun(childRunId, "CHILD_TOOL_RESOLUTION_FAILED", "controlled_child")
            return SubagentResult(
                childRunId = childRunId,
                report = ChildRunReport(unresolved = listOf("CHILD_TOOL_RESOLUTION_FAILED")),
            )
        }

        val preflight = ProviderPreflight.evaluate(
            ProviderPreflightRequest(
                mode = request.spec.mode,
                capabilities = model.effectiveCapabilityProfile(),
                resolvedFunctionToolCount = tools.size,
                configuredNativeToolCount = model.tools.size,
                requestedOutputTokens = exploreAssistant.maxTokens,
                outputReserveTokens = exploreAssistant.maxTokens ?: DEFAULT_OUTPUT_RESERVE_TOKENS,
                streamingRequested = exploreAssistant.streamOutput,
                reasoningRequested = exploreAssistant.reasoningLevel.isEnabled,
                multimodalInputRequested = false,
            ),
        )
        if (preflight.codes.isNotEmpty()) {
            agentRunRepository.recordStep(
                id = Uuid.random().toString(),
                runId = childRunId,
                kind = "provider_preflight",
                status = if (preflight.action == ProviderPreflightAction.BLOCK) AgentStepStatus.FAILED else AgentStepStatus.SUCCEEDED,
                summary = AgentStepSummary("provider_preflight", preflight.codes.joinToString(",") { it.name }),
            )
        }
        if (preflight.action == ProviderPreflightAction.BLOCK) {
            agentRunRepository.blockRun(
                childRunId,
                preflight.codes.joinToString("_") { it.name },
                category = "provider_capability",
            )
            return SubagentResult(childRunId, ChildRunReport(unresolved = preflight.codes.map { it.name }))
        }
        val requestModel = if (preflight.allowNativeTools) model else model.copy(tools = emptySet())
        val requestAssistant = exploreAssistant.copy(
            maxTokens = preflight.outputTokens,
            streamOutput = preflight.streaming,
            reasoningLevel = if (preflight.reasoning) exploreAssistant.reasoningLevel else ReasoningLevel.OFF,
        )
        agentRunRepository.transitionRun(childRunId, setOf(AgentRunStatus.PREFLIGHT), AgentRunStatus.RUNNING)

        Log.i(
            TAG,
            "start controlled explore child=$childRunId parent=$parentRunId tools=${tools.map { it.name }} maxSteps=$maxSteps"
        )
        request.processingStatus.value = "Explore subagent: investigating…"

        val seedMessages = listOf(UIMessage.user(task))
        var lastMessages: List<UIMessage> = seedMessages

        return try {
            withTimeout(budget.maxDurationMillis) {
                agentLoop.run(
                    settings = settings,
                    model = requestModel,
                    messages = seedMessages,
                    inputTransformers = request.inputTransformers,
                    outputTransformers = emptyList(),
                    assistant = requestAssistant,
                    memories = emptyList(),
                    tools = if (preflight.allowFunctionTools) tools else emptyList(),
                    workspace = request.assistant.workspaceId?.let { workspaceId ->
                        workspaceRepository.getById(workspaceId.toString())?.toWorkspace()
                    },
                    runRuntime = BudgetedChildRuntime(
                        PersistedAgentRunRuntime(agentRunRepository, childRunId),
                        budget.maxToolCalls,
                    ),
                    artifactRunScope = ToolArtifactRunScope(
                        assistantId = request.assistant.id.toString(),
                        conversationId = request.conversation.id.toString(),
                        runId = childRunId,
                    ),
                    maxSteps = maxSteps,
                    contextWindowTokenLimit = budget.maxContextTokens,
                    processingStatus = request.processingStatus,
                    conversationSystemPrompt = null,
                    conversationModeInjectionIds = emptySet(),
                    conversationLorebookIds = emptySet(),
                    workspaceCwd = request.conversation.workspaceCwd,
                    mode = request.spec.mode,
                    permissionPolicy = PermissionPolicy.compatibleDefault(injectPromptForWorkspace = true),
                    isSubagentRun = true,
                    allowParallelToolCalls = preflight.allowParallelToolCalls,
                    useClientGeneratedToolExecutionIdentity = preflight.useClientGeneratedToolExecutionIdentity,
                    onEvent = { event ->
                        if (event is me.rerere.rikkahub.data.ai.agent.AgentEvent.StepStarted) {
                            request.processingStatus.value =
                                "Explore subagent: step ${event.stepIndex + 1}/$maxSteps"
                        }
                    },
                ).collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> lastMessages = chunk.messages
                    }
                }
            }

            val report = controlledReport(extractFinalAssistantText(lastMessages))
            agentRunRepository.updateRunSummary(childRunId, AgentRunSummary(childReport = report))
            SubagentResult(childRunId, report)
        } catch (e: TimeoutCancellationException) {
            val report = ChildRunReport(unresolved = listOf("CHILD_DURATION_BUDGET_EXCEEDED"))
            agentRunRepository.updateRunSummary(childRunId, AgentRunSummary(childReport = report))
            SubagentResult(childRunId, report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Explore subagent failed", e)
            val report = ChildRunReport(
                unresolved = listOf(
                    if (e is ChildToolBudgetExceeded) "CHILD_TOOL_BUDGET_EXCEEDED" else "CHILD_EXECUTION_FAILED",
                ),
            )
            agentRunRepository.updateRunSummary(childRunId, AgentRunSummary(childReport = report))
            SubagentResult(
                childRunId = childRunId,
                report = report,
            )
        } finally {
            request.processingStatus.value = null
        }
    }

    private fun buildExploreAssistant(parent: Assistant, spec: SubagentSpec, budget: ChildRunBudget): Assistant {
        val system = buildString {
            append(EXPLORE_SYSTEM_PROMPT)
            if (spec.systemPrompt.isNotBlank()) {
                appendLine()
                appendLine()
                append(spec.systemPrompt.trim())
            }
            // 保留父助手中与代码库相关的轻量上下文（截断，避免撑爆）
            if (parent.systemPrompt.isNotBlank()) {
                val clipped = parent.systemPrompt.take(2000)
                appendLine()
                appendLine()
                appendLine("<parent_assistant_context>")
                appendLine(clipped)
                if (parent.systemPrompt.length > 2000) appendLine("...[truncated]")
                append("</parent_assistant_context>")
            }
        }
        return parent.copy(
            systemPrompt = system,
            maxTokens = minOf(parent.maxTokens ?: budget.maxOutputTokens, budget.maxOutputTokens),
            // 关闭会写入状态的能力；工具白名单会再挡一层
            enableMemory = false,
            localTools = emptyList(),
            enableWebSearch = false,
            enableRecentChatsReference = false,
            workspaceId = parent.workspaceId,
            enabledSkills = emptySet(),
            mcpServers = emptySet(),
            streamOutput = true,
            // 探索会话不注入角色卡类内容
            allowConversationSystemPrompt = false,
            allowConversationPromptInjection = false,
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            presetMessages = emptyList(),
        )
    }

    companion object {
        private const val MAX_CHILD_FINDINGS = 8
        private const val MAX_CHILD_SUMMARY_CHARS = 1_800
        private const val DEFAULT_OUTPUT_RESERVE_TOKENS = 1_024

        fun controlledReport(text: String): ChildRunReport {
            val sections = text.lineSequence().fold(LinkedHashMap<String, MutableList<String>>()) { result, line ->
                val heading = line.trim().removePrefix("## ").lowercase()
                if (heading in setOf("findings", "evidence paths", "relevant paths", "confidence", "open questions")) {
                    result.getOrPut(heading, ::ArrayList)
                } else if (result.isNotEmpty()) {
                    result.values.last() += line.trim().removePrefix("- ").take(300)
                }
                result
            }
            fun values(vararg names: String) = names.asSequence().flatMap { sections[it].orEmpty().asSequence() }
                .map(String::trim).map(::redact).filter(String::isNotBlank).take(MAX_CHILD_FINDINGS).toList()
            var remainingChars = MAX_CHILD_SUMMARY_CHARS
            fun bounded(entries: List<String>): List<String> = entries.mapNotNull { entry ->
                entry.take(remainingChars).takeIf(String::isNotBlank)?.also { remainingChars -= it.length }
            }
            val findings = bounded(values("findings"))
            val evidencePaths = bounded(values("evidence paths", "relevant paths").filter(::isRepositoryPath))
            val unresolved = bounded(values("open questions"))
            val confidence = values("confidence")
                .map(String::uppercase)
                .firstOrNull { it in setOf("HIGH", "MEDIUM", "LOW") }
                ?: if (findings.isEmpty()) "LOW" else "MEDIUM"
            return ChildRunReport(
                findings = findings,
                evidencePaths = evidencePaths,
                confidence = confidence,
                unresolved = unresolved,
            )
        }

        private fun redact(value: String): String = value.replace(
            Regex("(?:[A-Za-z]:[\\\\/]|/(?:Users|home|data)/)[^\\s`]+"),
            "[REDACTED_PATH]",
        )

        private fun isRepositoryPath(value: String): Boolean =
            value.startsWith("app/") || value.startsWith("ai/") || value.startsWith("common/") ||
                value.startsWith("document/") || value.startsWith("search/") || value.startsWith("speech/") ||
                value.startsWith("web/") || value.startsWith("workspace/") || value.startsWith("./")

        fun extractFinalAssistantText(messages: List<UIMessage>): String {
            val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?: return ""
            return lastAssistant.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
                .trim()
        }

    }

    private fun rejected(code: String) = SubagentResult(
        report = ChildRunReport(unresolved = listOf(code)),
    )

    internal class ChildToolBudgetExceeded : IllegalStateException()

    internal class BudgetedChildRuntime(
        private val delegate: AgentRunRuntime,
        private val maxToolCalls: Int,
    ) : AgentRunRuntime by delegate {
        private val observedExecutionIds = mutableSetOf<String>()

        override suspend fun toolObserved(
            stepId: String?,
            tool: UIMessagePart.Tool,
            descriptor: me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptor,
        ): String? {
            val executionId = delegate.toolObserved(stepId, tool, descriptor)
            val identity = executionId ?: "${tool.toolName}\u0000${tool.toolCallId}\u0000${tool.input.canonicalJson()}"
            if (observedExecutionIds.add(identity) && observedExecutionIds.size > maxToolCalls) {
                throw ChildToolBudgetExceeded()
            }
            return executionId
        }
    }

}
