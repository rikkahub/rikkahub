package me.rerere.rikkahub.data.ai.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.effectiveCapabilityProfile
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.mergeSystemMessages
import me.rerere.ai.util.ProviderStreamErrorCode
import me.rerere.ai.util.ProviderStreamException
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.agent.hooks.AgentHook
import me.rerere.rikkahub.data.ai.agent.hooks.NoOpAgentHook
import me.rerere.rikkahub.data.ai.agent.permission.CapabilityPolicy
import me.rerere.rikkahub.data.ai.agent.permission.CapabilityPolicyContext
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.permission.PolicyDecision
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptor
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry
import me.rerere.rikkahub.data.ai.agent.prompt.AgentPermissionPrompt
import me.rerere.rikkahub.data.ai.agent.subagent.EXPLORE_SUBAGENT_TOOL_NAME
import me.rerere.rikkahub.data.ai.agent.subagent.ControlledExploreBatch
import me.rerere.rikkahub.data.ai.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.ai.agent.context.ContextGovernor
import me.rerere.rikkahub.data.ai.agent.context.ContextPreflightRequest
import me.rerere.rikkahub.data.ai.agent.context.GovernedToolOutput
import me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope
import me.rerere.rikkahub.data.artifacts.ToolArtifactReference
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.AgentApprovalSummary
import me.rerere.rikkahub.data.ai.agent.canonicalJson
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.milliseconds
import me.rerere.workspace.Workspace

private const val TAG = "AgentLoop"

/**
 * 核心 Agent step 循环（自 GenerationHandler 抽出）。
 * 默认语义与改造前一致；[mode] / [permissionPolicy] / [hooks] 为增量能力。
 */
class AgentLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val hooks: AgentHook = NoOpAgentHook,
    private val contextGovernor: ContextGovernor,
    /** Controlled Explore batch limit; the safe product default is two. */
    private val maxParallelExploreChildren: Int = 2,
) {
    init {
        require(maxParallelExploreChildren in 1..2)
    }

    fun run(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        describedTools: List<DescribedTool> = tools.map { DescribedTool(it, ToolDescriptorRegistry.descriptorFor(it)) },
        workspace: Workspace? = null,
        runRuntime: AgentRunRuntime = NoOpAgentRunRuntime,
        artifactRunScope: ToolArtifactRunScope? = null,
        maxSteps: Int = 256,
        contextWindowTokenLimit: Int? = null,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        mode: AgentMode = AgentMode.CHAT,
        permissionPolicy: PermissionPolicy = PermissionPolicy.compatibleDefault(),
        isSubagentRun: Boolean = false,
        allowParallelToolCalls: Boolean = true,
        useClientGeneratedToolExecutionIdentity: Boolean = false,
        providerIdleTimeoutMillis: Long = DEFAULT_PROVIDER_IDLE_TIMEOUT_MILLIS,
        defaultToolTimeoutMillis: Long = DEFAULT_TOOL_TIMEOUT_MILLIS,
        onEvent: (AgentEvent) -> Unit = {},
    ): Flow<GenerationChunk> = flow {
        require(contextWindowTokenLimit == null || contextWindowTokenLimit > 0)
        require(providerIdleTimeoutMillis > 0)
        require(defaultToolTimeoutMillis > 0)
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages
        // Memory tools 已由 ToolRegistry 注入，此处不再重复添加
        val describedToolsInternal = describedTools
        val toolsInternal = describedToolsInternal.map(DescribedTool::tool)
        // Step indexes restart at zero for every Agent run. Scope generated IDs to this run so
        // earlier tool history cannot collide with a later run in the same conversation.
        val clientToolCallScope = Uuid.random().toString()
        var loopFinished = false
        try {
            for (stepIndex in 0 until maxSteps) {
                Log.i(TAG, "streamText: start step #$stepIndex (${model.id}) mode=$mode")
                onEvent(AgentEvent.StepStarted(stepIndex))
                val stepId = runRuntime.stepStarted(stepIndex)

                val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                    it.canResumeExecution
                } ?: emptyList()

                val toolsToProcess: List<UIMessagePart.Tool>

                if (pendingTools.isEmpty()) {
                    onEvent(AgentEvent.GenerationStarted(stepIndex))
                    generateInternal(
                        assistant = assistant,
                        settings = settings,
                        messages = messages,
                        onUpdateMessages = {
                            messages = it.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            )
                            emit(
                                GenerationChunk.Messages(
                                    messages.visualTransforms(
                                        transformers = outputTransformers,
                                        context = context,
                                        model = model,
                                        assistant = assistant,
                                        settings = settings
                                    )
                                )
                            )
                        },
                        transformers = inputTransformers,
                        model = model,
                        providerImpl = providerImpl,
                        provider = provider,
                        tools = toolsInternal,
                        memories = memories ?: emptyList(),
                        stream = assistant.streamOutput,
                        processingStatus = processingStatus,
                        conversationSystemPrompt = conversationSystemPrompt,
                        conversationModeInjectionIds = conversationModeInjectionIds,
                        conversationLorebookIds = conversationLorebookIds,
                        workspaceCwd = workspaceCwd,
                        workspace = workspace,
                        mode = mode,
                        permissionPolicy = permissionPolicy,
                        runRuntime = runRuntime,
                        artifactRunScope = artifactRunScope,
                        contextWindowTokenLimit = contextWindowTokenLimit,
                        stepId = stepId,
                        providerIdleTimeoutMillis = providerIdleTimeoutMillis,
                    )
                    messages = messages.visualTransforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                    messages = messages.onGenerationFinish(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                    messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                        finishedAt = Clock.System.now()
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                    )
                    emit(GenerationChunk.Messages(messages))

                    var tools = messages.last().getTools().filter { !it.isExecuted }
                    if (useClientGeneratedToolExecutionIdentity && tools.isNotEmpty()) {
                        tools = tools.mapIndexed { ordinal, tool ->
                            tool.copy(toolCallId = clientToolExecutionIdentity(clientToolCallScope, stepIndex, ordinal))
                        }
                        val identities = tools.iterator()
                        val lastMessage = messages.last()
                        messages = messages.dropLast(1) + lastMessage.copy(parts = lastMessage.parts.map { part ->
                            if (part is UIMessagePart.Tool && !part.isExecuted) identities.next() else part
                        })
                        emit(GenerationChunk.Messages(messages))
                    }
                    if (tools.isEmpty()) {
                        onEvent(AgentEvent.LoopFinished("no_tools"))
                        runRuntime.stepFinished(stepId, me.rerere.rikkahub.data.model.AgentStepStatus.SUCCEEDED)
                        runRuntime.finished("no_tools")
                        loopFinished = true
                        break
                    }

                    var hasPendingApproval = false
                    val updatedTools = tools.map { tool ->
                        val describedTool = describedToolsInternal.find { it.tool.name == tool.toolName }
                        val toolDef = describedTool?.tool
                        val descriptor =
                            describedTool?.descriptor ?: ToolDescriptorRegistry.descriptorFor(tool.toolName)
                        val decision = CapabilityPolicy.evaluate(
                            CapabilityPolicyContext(
                                assistant,
                                mode,
                                workspace,
                                descriptor,
                                permissionPolicy,
                                describedTool?.mcpServer,
                                isSubagentRun
                            )
                        )
                        runRuntime.policyDecision(tool, decision)
                        val executionId = runRuntime.toolObserved(stepId, tool, descriptor)
                        val args = tool.input.canonicalJson().let(json::parseToJsonElement)
                        val toolAsk = toolDef?.let {
                            runCatching {
                                permissionPolicy.requiresApproval(
                                    it,
                                    args,
                                    mode
                                )
                            }.getOrDefault(true)
                        } == true
                        when {
                            decision is PolicyDecision.Deny -> {
                                val completed = policyError(tool, decision)
                                if (runRuntime.toolFinished(
                                        executionId, me.rerere.rikkahub.data.model.ToolExecutionStatus.DENIED,
                                        output = completed.output,
                                        error = decision.code.name,
                                    )
                                ) completed else tool
                            }

                            (decision is PolicyDecision.Ask || toolAsk) &&
                                tool.approvalState is ToolApprovalState.Auto -> {
                                hasPendingApproval = true
                                val approvalId = checkNotNull(
                                    runRuntime.approvalRequested(
                                        executionId,
                                        tool,
                                        if (decision is PolicyDecision.Ask) decision else PolicyDecision.Ask(
                                            me.rerere.rikkahub.data.ai.agent.permission.PolicyCode.LEGACY_POLICY_ASK,
                                            "Tool metadata requires approval.",
                                        ),
                                        approvalRequestBinding(
                                            tool,
                                            stepId,
                                            descriptor,
                                            describedTool?.mcpServer,
                                            assistant,
                                            workspace,
                                            mode,
                                            permissionPolicy,
                                            toolAsk
                                        ),
                                    )
                                ) { "Unable to persist tool approval" }
                                tool.copy(
                                    approvalState = ToolApprovalState.Pending,
                                    toolExecutionId = executionId,
                                    approvalId = approvalId,
                                )
                            }

                            tool.approvalState is ToolApprovalState.Pending -> {
                                hasPendingApproval = true
                                tool
                            }

                            else -> tool
                        }
                    }

                    if (updatedTools != tools) {
                        val lastMessage = messages.last()
                        var toolIndex = 0
                        val updatedParts = lastMessage.parts.map { part ->
                            if (part is UIMessagePart.Tool && !part.isExecuted) {
                                updatedTools.getOrNull(toolIndex++) ?: part
                            } else {
                                part
                            }
                        }
                        messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                        emit(GenerationChunk.Messages(messages))
                    }

                    if (hasPendingApproval) {
                        Log.i(TAG, "generateText: waiting for tool approval")
                        onEvent(
                            AgentEvent.ToolApprovalPending(
                                updatedTools.filter { it.approvalState is ToolApprovalState.Pending }
                                    .map { it.toolName }
                            )
                        )
                        runRuntime.stepFinished(stepId, me.rerere.rikkahub.data.model.AgentStepStatus.SUCCEEDED)
                        runRuntime.waitingForApproval()
                        loopFinished = true
                        break
                    }

                    if (updatedTools.all { it.isExecuted }) {
                        runRuntime.stepFinished(stepId, me.rerere.rikkahub.data.model.AgentStepStatus.SUCCEEDED)
                        continue
                    }
                    toolsToProcess = updatedTools.filterNot { it.isExecuted }
                } else {
                    Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                    toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
                }

                val executedTools = arrayListOf<UIMessagePart.Tool>()
                var requeuedApproval: UIMessagePart.Tool? = null
                // Resolve every Explore call first. A child may only be launched after final policy,
                // approval binding, and the durable RUNNING transition have all completed.
                val exploreCalls = toolsToProcess.filter { it.toolName == EXPLORE_SUBAGENT_TOOL_NAME }

                class AuthorizedExplore(
                    val tool: UIMessagePart.Tool,
                    val definition: Tool,
                    val descriptor: ToolDescriptor,
                    val args: kotlinx.serialization.json.JsonElement,
                    val executionId: String?,
                )

                val precompletedExplore = linkedMapOf<String, UIMessagePart.Tool>()
                val authorizedExplore = arrayListOf<AuthorizedExplore>()
                for (tool in exploreCalls) {
                    val describedTool = describedToolsInternal.firstOrNull { it.tool.name == tool.toolName }
                    val descriptor = describedTool?.descriptor ?: ToolDescriptorRegistry.descriptorFor(tool.toolName)
                    val executionId = runRuntime.toolObserved(stepId, tool, descriptor)
                    when (tool.approvalState) {
                        is ToolApprovalState.Denied -> {
                            val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                            val completed = tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }),
                                    )
                                )
                            )
                            if (runRuntime.toolFinished(
                                    executionId,
                                    me.rerere.rikkahub.data.model.ToolExecutionStatus.DENIED,
                                    output = completed.output,
                                )
                            ) {
                                precompletedExplore[tool.toolCallId] = completed
                            }
                        }

                        is ToolApprovalState.Pending -> Unit
                        else -> {
                            val decision = CapabilityPolicy.evaluate(
                                CapabilityPolicyContext(
                                    assistant,
                                    mode,
                                    workspace,
                                    descriptor,
                                    permissionPolicy,
                                    describedTool?.mcpServer,
                                    isSubagentRun
                                ),
                            )
                            runRuntime.policyDecision(tool, decision)
                            if (decision is PolicyDecision.Deny) {
                                val completed = policyError(tool, decision)
                                if (runRuntime.toolFinished(
                                        executionId,
                                        me.rerere.rikkahub.data.model.ToolExecutionStatus.DENIED,
                                        output = completed.output,
                                        error = decision.code.name,
                                    )
                                ) {
                                    precompletedExplore[tool.toolCallId] = completed
                                }
                                continue
                            }
                            val definition = describedTool?.tool ?: error("Tool ${tool.toolName} not found")
                            val args = tool.input.canonicalJson().let(json::parseToJsonElement)
                            val toolAsk =
                                runCatching { permissionPolicy.requiresApproval(definition, args, mode) }.getOrDefault(
                                    true
                                )
                            if ((decision is PolicyDecision.Ask || toolAsk) && !runRuntime.approvedFor(
                                    executionId,
                                    tool,
                                    approvalBinding(
                                        tool,
                                        stepId,
                                        descriptor,
                                        describedTool.mcpServer,
                                        assistant,
                                        workspace,
                                        mode,
                                        permissionPolicy,
                                        toolAsk
                                    ),
                                )
                            ) {
                                val approvalId = checkNotNull(
                                    runRuntime.approvalRequested(
                                        executionId,
                                        tool,
                                        if (decision is PolicyDecision.Ask) decision else PolicyDecision.Ask(
                                            me.rerere.rikkahub.data.ai.agent.permission.PolicyCode.LEGACY_POLICY_ASK,
                                            "Tool approval requirements changed.",
                                        ),
                                        approvalRequestBinding(
                                            tool,
                                            stepId,
                                            descriptor,
                                            describedTool.mcpServer,
                                            assistant,
                                            workspace,
                                            mode,
                                            permissionPolicy,
                                            toolAsk
                                        ),
                                    )
                                ) { "Unable to requeue tool approval" }
                                requeuedApproval = requeuedApproval ?: tool.copy(
                                    approvalState = ToolApprovalState.Pending,
                                    toolExecutionId = executionId,
                                    approvalId = approvalId,
                                )
                            } else {
                                authorizedExplore += AuthorizedExplore(tool, definition, descriptor, args, executionId)
                            }
                        }
                    }
                }
                val parallelExploreResults = if (requeuedApproval == null) {
                    val admittedExploreCallIds = ControlledExploreBatch.admittedCallIds(
                        authorizedExplore.map { it.tool.toolCallId },
                        if (allowParallelToolCalls) maxParallelExploreChildren else 1,
                    )
                    val admitted = authorizedExplore.filter { it.tool.toolCallId in admittedExploreCallIds }
                    authorizedExplore.filterNot { it.tool.toolCallId in admittedExploreCallIds }.forEach { rejected ->
                        val completed = rejected.tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(buildJsonObject {
                                        put(
                                            "error",
                                            JsonPrimitive("CHILD_CONCURRENCY_LIMIT_EXCEEDED")
                                        )
                                    }),
                                )
                            )
                        )
                        if (runRuntime.toolFinished(
                                rejected.executionId,
                                me.rerere.rikkahub.data.model.ToolExecutionStatus.DENIED,
                                output = completed.output,
                                error = "CHILD_CONCURRENCY_LIMIT_EXCEEDED",
                            )
                        ) {
                            precompletedExplore[rejected.tool.toolCallId] = completed
                        }
                    }
                    val started = arrayListOf<AuthorizedExplore>()
                    admitted.forEach { explore ->
                        if (runRuntime.toolStarted(explore.executionId)) {
                            onEvent(AgentEvent.ToolExecutionStarted(explore.definition.name, explore.tool.toolCallId))
                            started += explore
                        }
                    }
                    supervisorScope {
                        started.map { explore ->
                            async {
                                explore.tool.toolCallId to executeAndGovernTool(
                                    definition = explore.definition,
                                    descriptor = explore.descriptor,
                                    args = explore.args,
                                    defaultToolTimeoutMillis = defaultToolTimeoutMillis,
                                    artifactRunScope = artifactRunScope,
                                    executionId = explore.executionId,
                                )
                            }
                        }.awaitAll().toMap()
                    }
                } else {
                    emptyMap()
                }
                for (tool in toolsToProcess) {
                    if (requeuedApproval != null) break
                    val describedTool = describedToolsInternal.find { it.tool.name == tool.toolName }
                    val descriptor = describedTool?.descriptor ?: ToolDescriptorRegistry.descriptorFor(tool.toolName)
                    val executionId = runRuntime.toolObserved(stepId, tool, descriptor)
                    precompletedExplore.remove(tool.toolCallId)?.let { completed ->
                        executedTools += completed
                        continue
                    }
                    parallelExploreResults[tool.toolCallId]?.let { result ->
                        val toolDef = describedTool?.tool ?: error("Tool ${tool.toolName} not found")
                        when (result) {
                            is ToolWatchdogOutcome.Completed -> commitToolResult(
                                runRuntime = runRuntime,
                                executionId = executionId,
                                status = me.rerere.rikkahub.data.model.ToolExecutionStatus.SUCCEEDED,
                                output = result.value.modelOutput,
                                artifact = result.value.reference,
                            ) {
                                executedTools += tool.copy(output = result.value.modelOutput)
                                onEvent(AgentEvent.ToolExecutionFinished(toolDef.name, tool.toolCallId, success = true))
                            }

                            is ToolWatchdogOutcome.Failed -> commitToolFailure(
                                runRuntime = runRuntime,
                                executionId = executionId,
                                tool = tool,
                                errorCode = TOOL_EXECUTION_FAILED_CODE,
                                cause = result.error,
                                executedTools = executedTools,
                                onEvent = onEvent,
                            )

                            is ToolWatchdogOutcome.TimedOut -> commitToolFailure(
                                runRuntime = runRuntime,
                                executionId = executionId,
                                tool = tool,
                                errorCode = TOOL_TIMEOUT_CODE,
                                cause = null,
                                executedTools = executedTools,
                                onEvent = onEvent,
                            )
                        }
                        continue
                    }
                    when (tool.approvalState) {
                        is ToolApprovalState.Denied -> {
                            val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                            val completed = tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(
                                                        "Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}"
                                                    )
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                            commitToolResult(
                                runRuntime = runRuntime,
                                executionId = executionId,
                                status = me.rerere.rikkahub.data.model.ToolExecutionStatus.DENIED,
                                output = completed.output,
                            ) {
                                executedTools += completed
                            }
                        }

                        is ToolApprovalState.Answered -> {
                            val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                            if (runRuntime.toolStarted(executionId)) {
                                val output = listOf(UIMessagePart.Text(answer))
                                commitToolResult(
                                    runRuntime = runRuntime,
                                    executionId = executionId,
                                    status = me.rerere.rikkahub.data.model.ToolExecutionStatus.SUCCEEDED,
                                    output = output,
                                ) {
                                    executedTools += tool.copy(output = output)
                                }
                            }
                        }

                        is ToolApprovalState.Pending -> Unit

                        else -> {
                            val decision = CapabilityPolicy.evaluate(
                                CapabilityPolicyContext(
                                    assistant,
                                    mode,
                                    workspace,
                                    descriptor,
                                    permissionPolicy,
                                    describedTool?.mcpServer,
                                    isSubagentRun
                                )
                            )
                            runRuntime.policyDecision(tool, decision)
                            if (decision is PolicyDecision.Deny) {
                                val completed = policyError(tool, decision)
                                commitToolResult(
                                    runRuntime = runRuntime,
                                    executionId = executionId,
                                    status = me.rerere.rikkahub.data.model.ToolExecutionStatus.DENIED,
                                    output = completed.output,
                                    error = decision.code.name,
                                ) {
                                    executedTools += completed
                                }
                                continue
                            }
                            val toolDef = describedTool?.tool ?: error("Tool ${tool.toolName} not found")
                            val args = tool.input.canonicalJson().let(json::parseToJsonElement)
                            val toolAsk = runCatching {
                                permissionPolicy.requiresApproval(
                                    toolDef,
                                    args,
                                    mode
                                )
                            }.getOrDefault(true)
                            val needsApproval = decision is PolicyDecision.Ask || toolAsk
                            if (needsApproval && !runRuntime.approvedFor(
                                    executionId,
                                    tool,
                                    approvalBinding(
                                        tool,
                                        stepId,
                                        descriptor,
                                        describedTool.mcpServer,
                                        assistant,
                                        workspace,
                                        mode,
                                        permissionPolicy,
                                        toolAsk
                                    ),
                                )
                            ) {
                                val approvalId = checkNotNull(
                                    runRuntime.approvalRequested(
                                        executionId,
                                        tool,
                                        if (decision is PolicyDecision.Ask) decision else PolicyDecision.Ask(
                                            me.rerere.rikkahub.data.ai.agent.permission.PolicyCode.LEGACY_POLICY_ASK,
                                            "Tool approval requirements changed.",
                                        ),
                                        approvalRequestBinding(
                                            tool,
                                            stepId,
                                            descriptor,
                                            describedTool.mcpServer,
                                            assistant,
                                            workspace,
                                            mode,
                                            permissionPolicy,
                                            toolAsk
                                        ),
                                    )
                                ) { "Unable to requeue tool approval" }
                                requeuedApproval = tool.copy(
                                    approvalState = ToolApprovalState.Pending,
                                    toolExecutionId = executionId,
                                    approvalId = approvalId,
                                )
                                break
                            }

                            if (!runRuntime.toolStarted(executionId)) continue
                            Log.i(TAG, "toolExecutionStarted executionId=$executionId toolType=${toolDef.name}")
                            onEvent(AgentEvent.ToolExecutionStarted(toolDef.name, tool.toolCallId))
                            when (val result = executeAndGovernTool(
                                definition = toolDef,
                                descriptor = descriptor,
                                args = args,
                                defaultToolTimeoutMillis = defaultToolTimeoutMillis,
                                artifactRunScope = artifactRunScope,
                                executionId = executionId,
                            )) {
                                is ToolWatchdogOutcome.Completed -> commitToolResult(
                                    runRuntime = runRuntime,
                                    executionId = executionId,
                                    status = me.rerere.rikkahub.data.model.ToolExecutionStatus.SUCCEEDED,
                                    output = result.value.modelOutput,
                                    artifact = result.value.reference,
                                ) {
                                    executedTools += tool.copy(output = result.value.modelOutput)
                                    onEvent(
                                        AgentEvent.ToolExecutionFinished(
                                            toolDef.name,
                                            tool.toolCallId,
                                            success = true,
                                        )
                                    )
                                }

                                is ToolWatchdogOutcome.Failed -> commitToolFailure(
                                    runRuntime = runRuntime,
                                    executionId = executionId,
                                    tool = tool,
                                    errorCode = TOOL_EXECUTION_FAILED_CODE,
                                    cause = result.error,
                                    executedTools = executedTools,
                                    onEvent = onEvent,
                                )

                                is ToolWatchdogOutcome.TimedOut -> commitToolFailure(
                                    runRuntime = runRuntime,
                                    executionId = executionId,
                                    tool = tool,
                                    errorCode = TOOL_TIMEOUT_CODE,
                                    cause = null,
                                    executedTools = executedTools,
                                    onEvent = onEvent,
                                )
                            }
                        }
                    }
                }

                if (requeuedApproval != null) {
                    val requeuedTool = checkNotNull(requeuedApproval)
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        when {
                            part is UIMessagePart.Tool && part.toolExecutionId == requeuedTool.toolExecutionId -> requeuedTool
                            part is UIMessagePart.Tool -> executedTools.find { it.toolExecutionId == part.toolExecutionId }
                                ?: part

                            else -> part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                    runRuntime.stepFinished(stepId, me.rerere.rikkahub.data.model.AgentStepStatus.SUCCEEDED)
                    runRuntime.waitingForApproval()
                    loopFinished = true
                    break
                }

                if (executedTools.isEmpty()) {
                    onEvent(AgentEvent.LoopFinished("no_executed_tools"))
                    runRuntime.stepFinished(stepId, me.rerere.rikkahub.data.model.AgentStepStatus.SUCCEEDED)
                    runRuntime.finished("no_executed_tools")
                    loopFinished = true
                    break
                }

                val lastMessage = messages.last()
                val updatedParts = lastMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool) {
                        executedTools.find { it.toolExecutionId == part.toolExecutionId } ?: part
                    } else part
                }
                messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                emit(
                    GenerationChunk.Messages(
                        messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                    )
                )
                runRuntime.stepFinished(stepId, me.rerere.rikkahub.data.model.AgentStepStatus.SUCCEEDED)
            }
            if (!loopFinished) {
                onEvent(AgentEvent.LoopFinished("max_steps"))
                runRuntime.finished("max_steps")
            }
        } catch (_: ContextBudgetBlockedException) {
            return@flow
        } catch (error: CancellationException) {
            convergeCancelledRun(runRuntime, error)
        } catch (error: Throwable) {
            runRuntime.failed(error)
            throw error
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeAndGovernTool(
        definition: Tool,
        descriptor: ToolDescriptor,
        args: kotlinx.serialization.json.JsonElement,
        defaultToolTimeoutMillis: Long,
        artifactRunScope: ToolArtifactRunScope?,
        executionId: String?,
    ): ToolWatchdogOutcome<GovernedToolOutput> {
        val execution = executeToolWithWatchdog(descriptor, defaultToolTimeoutMillis) {
            hooks.beforeTool(definition, args)
            val result = runCatching { definition.execute(args) }
            hooks.afterTool(definition, args, result)
            result.getOrThrow()
        }
        return when (execution) {
            is ToolWatchdogOutcome.Completed -> try {
                ToolWatchdogOutcome.Completed(
                    contextGovernor.governToolOutput(
                        runScope = artifactRunScope,
                        toolExecutionId = executionId ?: Uuid.random().toString(),
                        output = execution.value,
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ToolWatchdogOutcome.Failed(error)
            }

            is ToolWatchdogOutcome.Failed -> execution
            is ToolWatchdogOutcome.TimedOut -> execution
        }
    }

    private suspend fun commitToolFailure(
        runRuntime: AgentRunRuntime,
        executionId: String?,
        tool: UIMessagePart.Tool,
        errorCode: String,
        cause: Throwable?,
        executedTools: MutableList<UIMessagePart.Tool>,
        onEvent: (AgentEvent) -> Unit,
    ) {
        val traceId = executionId ?: tool.toolCallId.digestForLog().take(16)
        val output = toolFailureOutput(json, errorCode, traceId)
        commitToolResult(
            runRuntime = runRuntime,
            executionId = executionId,
            status = me.rerere.rikkahub.data.model.ToolExecutionStatus.FAILED,
            output = output,
            error = errorCode,
        ) {
            Log.w(
                TAG,
                "toolExecutionFailed traceId=$traceId errorType=${cause?.javaClass?.simpleName ?: errorCode}",
            )
            executedTools += tool.copy(output = output)
            onEvent(AgentEvent.ToolExecutionFinished(tool.toolName, tool.toolCallId, success = false))
        }
    }

    private fun policyError(tool: UIMessagePart.Tool, decision: PolicyDecision): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text(
                json.encodeToString(
                    buildJsonObject {
                        put("error", JsonPrimitive("${decision.code}: ${decision.reason}"))
                        put("category", JsonPrimitive("capability_policy"))
                    }
                )
            )
        )
    )

    private fun approvalBinding(
        tool: UIMessagePart.Tool,
        stepId: String?,
        descriptor: me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptor,
        mcpServer: me.rerere.rikkahub.data.ai.agent.permission.McpServerPolicyContext?,
        assistant: Assistant,
        workspace: Workspace?,
        mode: AgentMode,
        permissionPolicy: PermissionPolicy,
        dynamicToolAsk: Boolean,
    ): AgentApprovalSummary {
        val policyDigest = listOf(
            mode.name,
            permissionPolicy.byCategory.entries.sortedBy { it.key.name }.joinToString { "${it.key}:${it.value}" },
            descriptor.toString(),
            mcpServer.toString(),
            dynamicToolAsk.toString(),
        ).joinToString("|").digestForLog()
        return AgentApprovalSummary(
            stepId = stepId,
            toolName = tool.toolName,
            toolCallId = tool.toolCallId,
            inputSha256 = tool.input.canonicalJson().digestForLog(),
            assistantId = assistant.id.toString(),
            workspaceId = workspace?.id,
            mode = mode.name,
            policyDigest = policyDigest,
        )
    }

    private fun approvalRequestBinding(
        tool: UIMessagePart.Tool,
        stepId: String?,
        descriptor: me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptor,
        mcpServer: me.rerere.rikkahub.data.ai.agent.permission.McpServerPolicyContext?,
        assistant: Assistant,
        workspace: Workspace?,
        mode: AgentMode,
        permissionPolicy: PermissionPolicy,
        dynamicToolAsk: Boolean,
    ): AgentApprovalSummary = approvalBinding(
        tool, stepId, descriptor, mcpServer, assistant, workspace, mode, permissionPolicy, dynamicToolAsk,
    ).copy(
        expiresAt = System.currentTimeMillis() + APPROVAL_TTL_MILLIS,
    )

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        workspace: Workspace? = null,
        mode: AgentMode = AgentMode.CHAT,
        permissionPolicy: PermissionPolicy = PermissionPolicy.compatibleDefault(),
        runRuntime: AgentRunRuntime,
        artifactRunScope: ToolArtifactRunScope?,
        contextWindowTokenLimit: Int?,
        stepId: String?,
        providerIdleTimeoutMillis: Long,
    ) {
        val baseSystem = buildString {
            val effectiveSystemPrompt =
                if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                    conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            if (effectiveSystemPrompt.isNotBlank()) append(effectiveSystemPrompt)
            val permissionBlock = AgentPermissionPrompt.build(mode, permissionPolicy)
            if (permissionBlock.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(permissionBlock)
            }
        }
        val memoryPrompt = if (assistant.enableMemory) buildMemoryPrompt(memories = memories) else ""
        val toolSchemaPrompt = tools.map { it.systemPrompt(model, messages) }
            .filter(String::isNotBlank)
            .joinToString("\n")
        val baseSystemMessage = baseSystem.takeIf(String::isNotBlank)?.let { UIMessage.system(it) }
        val memoryMessage = memoryPrompt.takeIf(String::isNotBlank)?.let { UIMessage.system(it) }
        val toolSchemaMessage = toolSchemaPrompt.takeIf(String::isNotBlank)?.let { UIMessage.system(it) }
        val recentUserMessageIds = messages.filter { it.role == me.rerere.ai.core.MessageRole.USER }
            .takeLast(1)
            .mapTo(mutableSetOf()) { it.id }
        val internalMessages = buildList {
            baseSystemMessage?.let(::add)
            memoryMessage?.let(::add)
            toolSchemaMessage?.let(::add)
            addAll(messages)
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
            workspace = workspace,
        )
        val contextResult = contextGovernor.preflight(
            ContextPreflightRequest(
                messages = internalMessages,
                systemMessageIds = setOfNotNull(baseSystemMessage?.id),
                memoryMessageIds = setOfNotNull(memoryMessage?.id),
                toolSchemaMessageIds = setOfNotNull(toolSchemaMessage?.id),
                recentUserMessageIds = recentUserMessageIds,
                toolSchemaDefinition = tools.joinToString("\n") { tool ->
                    "${tool.name}\n${tool.description}\n${tool.parameters()}"
                },
                requestedOutputTokens = assistant.maxTokens,
                maxContextWindowTokens = contextWindowTokenLimit,
                capabilityProfile = model.effectiveCapabilityProfile(),
                artifactRunScope = artifactRunScope,
            ),
        )
        if (contextResult.blocked) {
            runRuntime.contextBlocked(contextResult.plan)
            runRuntime.stepFinished(stepId, me.rerere.rikkahub.data.model.AgentStepStatus.FAILED)
            throw ContextBudgetBlockedException()
        }
        runRuntime.contextPlanned(contextResult.plan)

        // Keep artifact references for later turns without persisting any transformer-only additions.
        var messages: List<UIMessage> = messages.withGovernedToolOutputs(contextResult.governedMessages)
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = contextResult.plan.reservedOutputTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        runRuntime.modelCallStarted(stepId)
        var modelCallSucceeded = false
        var modelUsage: TokenUsage? = null
        suspend fun acceptProviderChunk(chunk: MessageChunk) {
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                modelUsage = modelUsage.merge(usage)
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(usage = message.usage.merge(usage))
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
        try {
            if (stream) {
                collectProviderWithFallback(
                    stream = {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = contextResult.messages.mergeSystemMessages(),
                            params = params
                        ).withProviderIdleWatchdog(providerIdleTimeoutMillis)
                    },
                    fallback = {
                        awaitProviderWithIdleWatchdog(providerIdleTimeoutMillis) {
                            providerImpl.generateText(
                                providerSetting = provider,
                                messages = contextResult.messages.mergeSystemMessages(),
                                params = params,
                            )
                        }
                    },
                    onChunk = ::acceptProviderChunk,
                )
            } else {
                val chunk = awaitProviderWithIdleWatchdog(providerIdleTimeoutMillis) {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = contextResult.messages.mergeSystemMessages(),
                        params = params,
                    )
                }
                acceptProviderChunk(chunk)
            }
            modelCallSucceeded = true
        } finally {
            runRuntime.modelCallFinished(
                stepId,
                modelCallSucceeded,
                modelUsage?.promptTokens,
                modelUsage?.completionTokens,
            )
        }
    }

}

internal suspend fun collectProviderWithFallback(
    stream: suspend () -> Flow<MessageChunk>,
    fallback: suspend () -> MessageChunk,
    onChunk: suspend (MessageChunk) -> Unit,
) {
    var meaningfulProgress = false
    try {
        stream().collect { chunk ->
            meaningfulProgress = meaningfulProgress || chunk.hasMeaningfulProviderProgress()
            onChunk(chunk)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (!error.canFallbackFromEmptyStream(meaningfulProgress)) {
            throw error.asUserFacingProviderFailure("Provider stream failed")
        }
        val fallbackChunk = try {
            fallback()
        } catch (fallbackError: CancellationException) {
            throw fallbackError
        } catch (fallbackError: Throwable) {
            throw fallbackError.asUserFacingProviderFailure(
                "Provider request failed after stream fallback",
            )
        }
        onChunk(fallbackChunk)
    }
}

internal fun MessageChunk.hasMeaningfulProviderProgress(): Boolean = choices.any { choice ->
    (!choice.finishReason.isNullOrBlank() && choice.finishReason != "unknown") ||
        sequenceOf(choice.delta, choice.message).filterNotNull().any { message ->
            message.parts.any { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.isNotBlank()
                    is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
                    is UIMessagePart.Tool -> true
                    else -> false
                }
            }
        }
}

internal fun Throwable.canFallbackFromEmptyStream(hasMeaningfulProgress: Boolean): Boolean {
    if (hasMeaningfulProgress) return false
    val streamError = this as? ProviderStreamException ?: return false
    return streamError.code == ProviderStreamErrorCode.STREAM_UPSTREAM_FAILURE ||
        streamError.code == ProviderStreamErrorCode.STREAM_INCOMPLETE
}

internal fun Throwable.asUserFacingProviderFailure(prefix: String): RuntimeException {
    val detail = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .joinToString(" | ")
        .ifBlank { javaClass.simpleName }
        .replace(PROVIDER_AUTH_HEADER_PATTERN) { match -> "${match.groupValues[1]} [redacted]" }
        .replace(PROVIDER_SECRET_PATTERN) { match -> "${match.groupValues[1]} [redacted]" }
        .replace(Regex("\\s+"), " ")
        .take(MAX_PROVIDER_ERROR_LENGTH)
    return RuntimeException("$prefix: $detail", this)
}

private val PROVIDER_SECRET_PATTERN = Regex(
    "(?i)\\b(api[-_ ]?key|bearer|token|secret)\\b\\s*[:=]?\\s*[^\\s,;]+",
)
private val PROVIDER_AUTH_HEADER_PATTERN = Regex(
    "(?i)\\b(authorization)\\b\\s*[:=]?\\s*(?:bearer\\s+)?[^\\s,;]+",
)
private const val MAX_PROVIDER_ERROR_LENGTH = 600

internal sealed interface ToolWatchdogOutcome<out T> {
    data class Completed<T>(val value: T) : ToolWatchdogOutcome<T>
    data class Failed(val error: Throwable) : ToolWatchdogOutcome<Nothing>
    data class TimedOut(val timeoutMillis: Long) : ToolWatchdogOutcome<Nothing>
}

internal suspend fun <T> executeToolWithWatchdog(
    descriptor: ToolDescriptor,
    defaultTimeoutMillis: Long,
    block: suspend () -> T,
): ToolWatchdogOutcome<T> {
    require(defaultTimeoutMillis > 0)
    val timeoutMillis = descriptor.timeoutMillis ?: defaultTimeoutMillis
    return try {
        ToolWatchdogOutcome.Completed(withTimeout(timeoutMillis) { block() })
    } catch (error: TimeoutCancellationException) {
        // A parent cancellation may race the local watchdog. It always wins over a TOOL_TIMEOUT result.
        currentCoroutineContext().ensureActive()
        ToolWatchdogOutcome.TimedOut(timeoutMillis)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        ToolWatchdogOutcome.Failed(error)
    }
}

internal suspend fun commitToolResult(
    runRuntime: AgentRunRuntime,
    executionId: String?,
    status: me.rerere.rikkahub.data.model.ToolExecutionStatus,
    output: List<UIMessagePart> = emptyList(),
    error: String? = null,
    artifact: ToolArtifactReference? = null,
    onCommitted: () -> Unit,
): Boolean {
    val committed = runRuntime.toolFinished(executionId, status, output, error, artifact)
    if (committed) onCommitted()
    return committed
}

internal fun toolFailureOutput(json: Json, errorCode: String, traceId: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        json.encodeToString(
            buildJsonObject {
                put("code", JsonPrimitive(errorCode))
                put(
                    "message",
                    JsonPrimitive(
                        if (errorCode == TOOL_TIMEOUT_CODE) {
                            "Tool execution timed out."
                        } else {
                            "Tool execution failed. Check the request and retry."
                        }
                    ),
                )
                put("trace_id", JsonPrimitive(traceId))
            }
        )
    )
)

internal suspend fun convergeCancelledRun(
    runRuntime: AgentRunRuntime,
    cancellation: CancellationException,
): Nothing {
    try {
        withContext(NonCancellable) { runRuntime.cancelled() }
    } catch (convergenceError: Throwable) {
        if (convergenceError !== cancellation) cancellation.addSuppressed(convergenceError)
    }
    throw cancellation
}

internal class ProviderIdleTimeoutException(
    val timeoutMillis: Long,
) : IllegalStateException(PROVIDER_IDLE_TIMEOUT_CODE)

@OptIn(FlowPreview::class)
internal fun <T> Flow<T>.withProviderIdleWatchdog(timeoutMillis: Long): Flow<T> = flow {
    require(timeoutMillis > 0)
    try {
        this@withProviderIdleWatchdog.timeout(timeoutMillis.milliseconds).collect { emit(it) }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ProviderIdleTimeoutException(timeoutMillis)
    }
}

internal suspend fun <T> awaitProviderWithIdleWatchdog(
    timeoutMillis: Long,
    block: suspend () -> T,
): T {
    require(timeoutMillis > 0)
    return try {
        withTimeout(timeoutMillis) { block() }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ProviderIdleTimeoutException(timeoutMillis)
    }
}

private class ContextBudgetBlockedException : IllegalStateException("CONTEXT_BUDGET_EXCEEDED")

internal const val TOOL_TIMEOUT_CODE = "TOOL_TIMEOUT"
internal const val TOOL_EXECUTION_FAILED_CODE = "TOOL_EXECUTION_FAILED"
internal const val PROVIDER_IDLE_TIMEOUT_CODE = "PROVIDER_IDLE_TIMEOUT"
private const val DEFAULT_PROVIDER_IDLE_TIMEOUT_MILLIS = 45_000L
private const val DEFAULT_TOOL_TIMEOUT_MILLIS = 30_000L

private fun List<UIMessage>.withGovernedToolOutputs(governedMessages: List<UIMessage>): List<UIMessage> {
    val governedById = governedMessages.associateBy(UIMessage::id)
    return map { message ->
        val governed = governedById[message.id] ?: return@map message
        message.copy(parts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Tool -> {
                    val replacement = governed.getTools().firstOrNull { it.toolCallId == part.toolCallId }
                    replacement?.let { part.copy(output = it.output) } ?: part
                }

                is UIMessagePart.ToolResult -> {
                    @Suppress("DEPRECATION")
                    val replacement = governed.parts.filterIsInstance<UIMessagePart.ToolResult>()
                        .firstOrNull { it.toolCallId == part.toolCallId }
                    replacement?.let { part.copy(content = it.content) } ?: part
                }

                else -> part
            }
        })
    }
}

private fun String.digestForLog(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { "%02x".format(it) }

private const val APPROVAL_TTL_MILLIS = 5 * 60 * 1000L

/** Provider call IDs with UNKNOWN stability are scoped to a run, never reused by a later turn. */
internal fun clientToolExecutionIdentity(runScope: String, stepIndex: Int, ordinal: Int): String =
    "client-$runScope-$stepIndex-$ordinal"
