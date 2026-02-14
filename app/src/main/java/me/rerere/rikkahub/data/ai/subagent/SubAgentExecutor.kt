package me.rerere.rikkahub.data.ai.subagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentStatus
import me.rerere.rikkahub.data.model.SubAgentToolSet
import me.rerere.rikkahub.data.model.WorkflowPhase
import kotlin.uuid.Uuid

private const val TAG = "SubAgentExecutor"

/**
 * 子代理执行器
 * 负责执行子代理任务，管理工具暴露和结果返回
 */
class SubAgentExecutor(
    private val generationHandler: GenerationHandler,
    private val context: Context,
) {
    /**
     * 执行子代理任务（流式返回进度）
     *
     * @param subAgent 子代理配置
     * @param task 任务描述
     * @param context 相关上下文
     * @param files 相关文件路径列表
     * @param sandboxId 沙箱ID（用于文件隔离）
     * @param settings 当前设置
     * @param parentModel 主对话使用的模型（子代理默认继承此模型）
     * @param parentWorkflowPhase 父代理的Workflow阶段（用于权限控制）
     * @param containerEnabled 容器是否可用
     * @param localTools 本地工具实例（用于创建子代理工具）
     * @param mcpTools 可用的MCP工具
     * @return 流式的子代理执行进度
     */
    fun execute(
        subAgent: SubAgent,
        task: String,
        context: String = "",
        files: List<String> = emptyList(),
        sandboxId: Uuid,
        settings: Settings,
        parentModel: me.rerere.ai.provider.Model,
        parentWorkflowPhase: WorkflowPhase? = null,
        containerEnabled: Boolean,
        localTools: LocalTools,
        mcpTools: List<Tool> = emptyList(),
    ): Flow<SubAgentProgress> = flow {
        Log.d(TAG, "Executing sub-agent ${subAgent.name} for task: ${task.take(50)}...")

        val startTime = System.currentTimeMillis()

        try {
            // 1. 构建子代理的系统提示词
            val systemPrompt = buildSubAgentSystemPrompt(subAgent, files)

            // 2. 构建消息列表
            val messages = buildSubAgentMessages(systemPrompt, task, context)

            // 3. 确定模型
            val model = subAgent.modelId?.let {
                settings.findModelById(it)
            } ?: parentModel

            // 4. 准备子代理的工具集（受限的）
            val tools = buildSubAgentTools(
                toolSet = subAgent.allowedTools,
                sandboxId = sandboxId,
                containerEnabled = containerEnabled,
                localTools = localTools,
                mcpTools = mcpTools,
                settings = settings
            )

            // 5. 创建临时Assistant配置
            val assistant = Assistant(
                id = Uuid.random(),
                name = subAgent.name,
                systemPrompt = systemPrompt,
                temperature = subAgent.temperature,
                maxTokens = subAgent.maxTokens,
                contextMessageSize = 0,  // 0 = 不限制消息数量
            )

            // 6. 根据子代理类型确定WorkflowPhase
            val effectivePhase = when (subAgent.name) {
                "Explore", "Plan" -> WorkflowPhase.PLAN
                "Task" -> parentWorkflowPhase
                else -> parentWorkflowPhase
            }

            // 7. 执行生成（流式）
            var resultText = ""
            var finalMessages: List<UIMessage> = emptyList()
            var stepIndex = 0
            var totalToolCalls = 0
            val toolCallsInCurrentStep = mutableListOf<String>()

            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = tools,
                maxSteps = 50,
                workflowPhase = effectivePhase,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        finalMessages = chunk.messages
                        val lastMessage = chunk.messages.lastOrNull()

                        // 检测步骤变化（通过消息数量或工具调用变化）
                        val currentStep = chunk.messages.size
                        if (currentStep > stepIndex) {
                            stepIndex = currentStep
                            toolCallsInCurrentStep.clear()
                        }

                        // 提取工具调用
                        lastMessage?.parts
                            ?.filterIsInstance<UIMessagePart.Tool>()
                            ?.let { toolCalls ->
                                toolCalls.forEach { toolCall ->
                                    if (!toolCallsInCurrentStep.contains(toolCall.toolName)) {
                                        toolCallsInCurrentStep.add(toolCall.toolName)
                                        totalToolCalls++  // 累计工具调用次数
                                        // Emit工具调用更新
                                        emit(SubAgentProgress.ToolCallUpdate(
                                            toolName = toolCall.toolName,
                                            status = "executing",
                                            result = null
                                        ))
                                    }
                                }
                            }

                        // 提取文本内容（实时流式输出）
                        val textContent = lastMessage?.toText() ?: ""
                        if (textContent.isNotEmpty() && textContent != resultText) {
                            val newContent = textContent.removePrefix(resultText)
                            resultText = textContent

                            // Emit文本块（实时输出）
                            emit(SubAgentProgress.TextChunk(
                                content = newContent,
                                isThinking = lastMessage?.parts?.any { it is UIMessagePart.Reasoning } ?: false
                            ))
                        }

                        // Emit步骤更新
                        emit(SubAgentProgress.StepUpdate(
                            stepIndex = stepIndex,
                            totalSteps = 50, // maxSteps
                            currentMessage = textContent.take(100), // 摘要
                            toolCalls = toolCallsInCurrentStep.toList(),
                            totalToolCalls = totalToolCalls
                        ))
                    }
                }
            }

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "Sub-agent ${subAgent.name} completed in ${duration}ms")

            val result = SubAgentResult(
                success = true,
                result = resultText,
                duration = duration,
                messages = finalMessages
            )

            // Emit完成事件
            emit(SubAgentProgress.Complete(result))

        } catch (e: Exception) {
            Log.e(TAG, "Sub-agent ${subAgent.name} failed", e)
            val duration = System.currentTimeMillis() - startTime
            val result = SubAgentResult(
                success = false,
                result = "",
                error = e.message ?: "Unknown error",
                duration = duration
            )

            // Emit错误事件
            emit(SubAgentProgress.Error(e.message ?: "Unknown error"))
            emit(SubAgentProgress.Complete(result))
        }
    }

    /**
     * 构建子代理的系统提示词
     */
    private fun buildSubAgentSystemPrompt(subAgent: SubAgent, files: List<String>): String {
        return buildString {
            appendLine(subAgent.systemPrompt)
            if (files.isNotEmpty()) {
                appendLine()
                appendLine("=== 相关文件 ===")
                files.forEach { file ->
                    appendLine("- $file")
                }
                appendLine("你可以使用 sandbox_file 的 read 操作读取这些文件。")
            }
        }
    }

    /**
     * 构建子代理的消息列表
     */
    private fun buildSubAgentMessages(
        systemPrompt: String,
        task: String,
        context: String
    ): List<UIMessage> {
        val userMessage = buildString {
            appendLine("=== 任务 ===")
            appendLine(task)
            if (context.isNotEmpty()) {
                appendLine()
                appendLine("=== 上下文 ===")
                appendLine(context)
            }
            appendLine()
            appendLine("请完成上述任务并返回清晰的结果。不要反问用户，直接执行任务。")
        }

        return listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text(systemPrompt))
            ),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(userMessage))
            )
        )
    }

    /**
     * 为子代理构建受限的工具集
     */
    private fun buildSubAgentTools(
        toolSet: SubAgentToolSet,
        sandboxId: Uuid,
        containerEnabled: Boolean,
        localTools: LocalTools,
        mcpTools: List<Tool>,
        settings: Settings
    ): List<Tool> {
        val tools = mutableListOf<Tool>()

        // 网络搜索工具
        if (toolSet.enableWebSearch) {
            tools.addAll(createSearchTools(context, settings))
        }

        // 沙箱文件操作（所有子代理默认都有）
        if (toolSet.enableSandboxFile) {
            tools.add(localTools.createSandboxFileTool(sandboxId))
        }

        // Python执行
        if (toolSet.enableSandboxPython) {
            tools.add(localTools.createSandboxPythonTool(sandboxId))
        }

        // Shell执行（完整权限）
        if (toolSet.enableSandboxShell) {
            tools.add(localTools.createSandboxShellTool(sandboxId))
        }

        // Shell执行（只读模式，白名单限制）
        if (toolSet.enableSandboxShellReadonly) {
            tools.add(localTools.createSandboxShellReadonlyTool(sandboxId))
        }

        // 数据处理
        if (toolSet.enableSandboxData) {
            tools.add(localTools.createSandboxDataTool(sandboxId))
        }

        // 开发工具
        if (toolSet.enableSandboxDev) {
            tools.add(localTools.createSandboxDevTool(sandboxId))
        }

        // 容器运行时工具（仅当容器启用且正在运行）
        if (toolSet.enableContainer && containerEnabled) {
            // TODO: createContainerPythonTool 方法暂未实现，已通过 shell 工具支持 Python（apk add python3）
            // tools.add(localTools.createContainerPythonTool(sandboxId))
            tools.add(localTools.createContainerShellTool(sandboxId))
        }

        // MCP工具（根据allowedMcpServers过滤）
        if (toolSet.allowedMcpServers.isNotEmpty()) {
            // 构建允许的服务器ID集合
            val allowedServerIds = toolSet.allowedMcpServers

            // 从settings中获取每个服务器对应的工具名称
            val serverToolNames = settings.mcpServers
                .filter { serverConfig -> serverConfig.id in allowedServerIds }
                .flatMap { serverConfig -> serverConfig.commonOptions.tools }
                .map { tool -> "mcp__${tool.name}" }
                .toSet()

            // 只添加来自允许服务器的工具
            mcpTools.filter { tool -> tool.name in serverToolNames }
                .let { tools.addAll(it) }
        }

        return tools
    }
}

/**
 * 子代理执行进度（用于流式更新UI）
 */
sealed class SubAgentProgress {
    /**
     * 执行步骤更新
     */
    data class StepUpdate(
        val stepIndex: Int,
        val totalSteps: Int? = null,
        val currentMessage: String = "",
        val toolCalls: List<String> = emptyList(),
        val totalToolCalls: Int = 0,  // 累计工具调用次数
    ) : SubAgentProgress()

    /**
     * 工具调用更新
     */
    data class ToolCallUpdate(
        val toolName: String,
        val status: String, // "pending", "executing", "completed", "failed"
        val result: String? = null,
    ) : SubAgentProgress()

    /**
     * 流式文本输出（子代理的实时输出）
     */
    data class TextChunk(
        val content: String,
        val isThinking: Boolean = false,
    ) : SubAgentProgress()

    /**
     * 执行完成
     */
    data class Complete(
        val result: SubAgentResult,
    ) : SubAgentProgress()

    /**
     * 执行错误
     */
    data class Error(
        val error: String,
    ) : SubAgentProgress()
}

/**
 * 子代理执行结果
 */
data class SubAgentResult(
    val success: Boolean,
    val result: String,
    val error: String? = null,
    val duration: Long,
    val messages: List<UIMessage> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("success", success)
        put("result", result)
        error?.let { put("error", it) }
        put("duration_ms", duration)
    }
}
