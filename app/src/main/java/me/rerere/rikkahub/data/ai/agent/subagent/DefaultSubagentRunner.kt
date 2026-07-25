package me.rerere.rikkahub.data.ai.agent.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.agent.AgentLoop
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant

private const val TAG = "DefaultSubagentRunner"

/**
 * 真正运行隔离 Explore 会话：独立消息列表 + PLAN 模式 + 只读工具白名单 + AgentLoop。
 */
class DefaultSubagentRunner(
    private val agentLoop: AgentLoop,
    private val toolRegistry: ToolRegistry,
) : SubagentRunner {

    override suspend fun run(request: SubagentRequest): SubagentResult {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return SubagentResult(
                summary = "",
                success = false,
                error = "task is required",
            )
        }

        val settings = request.settings
        val model = settings.findModelById(
            request.assistant.chatModelId ?: settings.chatModelId
        ) ?: return SubagentResult(
            summary = "",
            success = false,
            error = "Chat model not found for subagent",
        )

        if (!model.abilities.contains(ModelAbility.TOOL)) {
            return SubagentResult(
                summary = "",
                success = false,
                error = "Model does not support tools; cannot run explore subagent",
            )
        }

        val maxSteps = request.spec.maxSteps.coerceIn(1, SubagentSpec.HARD_MAX_STEPS)
        val exploreAssistant = buildExploreAssistant(request.assistant, request.spec)

        val resolveCtx = ToolResolveContext(
            settings = settings,
            assistant = exploreAssistant,
            conversation = request.conversation,
            mode = request.spec.mode,
            permissionPolicy = PermissionPolicy.compatibleDefault(injectPromptForWorkspace = true),
            isSubagentRun = true,
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
            return SubagentResult(
                summary = "",
                success = false,
                error = "Failed to resolve explore tools: ${e.message}",
            )
        }

        Log.i(
            TAG,
            "start explore subagent task='${task.take(80)}' tools=${tools.map { it.name }} maxSteps=$maxSteps"
        )
        request.processingStatus.value = "Explore subagent: investigating…"

        val seedMessages = listOf(UIMessage.user(task))
        var lastMessages: List<UIMessage> = seedMessages
        var stepsUsed = 0

        return try {
            agentLoop.run(
                settings = settings,
                model = model,
                messages = seedMessages,
                inputTransformers = request.inputTransformers,
                outputTransformers = emptyList(),
                assistant = exploreAssistant,
                memories = emptyList(),
                tools = tools,
                maxSteps = maxSteps,
                processingStatus = request.processingStatus,
                conversationSystemPrompt = null,
                conversationModeInjectionIds = emptySet(),
                conversationLorebookIds = emptySet(),
                workspaceCwd = request.conversation.workspaceCwd,
                mode = request.spec.mode,
                permissionPolicy = PermissionPolicy.compatibleDefault(injectPromptForWorkspace = true),
                onEvent = { event ->
                    // 粗略 step 计数：GenerationStarted
                    if (event is me.rerere.rikkahub.data.ai.agent.AgentEvent.StepStarted) {
                        stepsUsed = event.stepIndex + 1
                        request.processingStatus.value =
                            "Explore subagent: step ${event.stepIndex + 1}/$maxSteps"
                    }
                },
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> lastMessages = chunk.messages
                }
            }

            val summary = extractFinalAssistantText(lastMessages)
            val toolsUsed = extractToolNames(lastMessages)
            val trace = extractTrace(lastMessages)
            val rawNotes = buildRawNotes(lastMessages, toolsUsed)

            if (summary.isBlank()) {
                SubagentResult(
                    summary = rawNotes.ifBlank { "(Explore subagent produced no text summary.)" },
                    rawNotes = rawNotes,
                    stepsUsed = stepsUsed,
                    toolsUsed = toolsUsed,
                    trace = trace,
                    success = toolsUsed.isNotEmpty(),
                    error = if (toolsUsed.isEmpty()) "No summary and no tools used" else null,
                )
            } else {
                SubagentResult(
                    summary = summary,
                    rawNotes = rawNotes,
                    stepsUsed = stepsUsed,
                    toolsUsed = toolsUsed,
                    trace = trace,
                    success = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Explore subagent failed", e)
            val toolsUsed = extractToolNames(lastMessages)
            SubagentResult(
                summary = extractFinalAssistantText(lastMessages),
                rawNotes = buildRawNotes(lastMessages, toolsUsed),
                stepsUsed = stepsUsed,
                toolsUsed = toolsUsed,
                trace = extractTrace(lastMessages),
                success = false,
                error = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            request.processingStatus.value = null
        }
    }

    private fun buildExploreAssistant(parent: Assistant, spec: SubagentSpec): Assistant {
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
            // 关闭会写入状态的能力；工具白名单会再挡一层
            enableMemory = false,
            localTools = emptyList(),
            // 保留搜索 / workspace / skills / 对话检索开关，由 ToolRegistry + 白名单决定
            enableWebSearch = parent.enableWebSearch,
            enableRecentChatsReference = parent.enableRecentChatsReference,
            workspaceId = parent.workspaceId,
            enabledSkills = parent.enabledSkills,
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
        fun extractFinalAssistantText(messages: List<UIMessage>): String {
            val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?: return ""
            return lastAssistant.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
                .trim()
        }

        fun extractToolNames(messages: List<UIMessage>): List<String> {
            return messages
                .flatMap { it.getTools() }
                .map { it.toolName }
                .distinct()
        }

        fun extractTrace(
            messages: List<UIMessage>,
            maxSteps: Int = MAX_TRACE_STEPS,
            inputMax: Int = TRACE_INPUT_CHARS,
            outputMax: Int = TRACE_OUTPUT_CHARS,
        ): List<SubagentTraceStep> {
            var index = 0
            return messages
                .flatMap { it.getTools() }
                .filter { it.isExecuted }
                .take(maxSteps)
                .map { tool ->
                    val output = tool.output
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                    val isError = output.contains("\"error\"") ||
                        output.startsWith("[") && output.contains("Exception")
                    SubagentTraceStep(
                        index = index++,
                        toolName = tool.toolName,
                        inputPreview = tool.input.take(inputMax),
                        outputPreview = output.take(outputMax),
                        isError = isError,
                    )
                }
        }

        fun buildRawNotes(messages: List<UIMessage>, toolsUsed: List<String>): String =
            buildString {
                if (toolsUsed.isNotEmpty()) {
                    appendLine("Tools used: ${toolsUsed.joinToString(", ")}")
                    appendLine()
                }
                extractTrace(messages).forEach { step ->
                    appendLine("### ${step.index + 1}. ${step.toolName}")
                    if (step.inputPreview.isNotBlank()) {
                        appendLine("input: ${step.inputPreview}")
                    }
                    if (step.outputPreview.isNotBlank()) {
                        appendLine("output: ${step.outputPreview}")
                    }
                    appendLine()
                }
            }.trim()

        private const val MAX_TRACE_STEPS = 24
        private const val TRACE_INPUT_CHARS = 400
        private const val TRACE_OUTPUT_CHARS = 600
    }
}
