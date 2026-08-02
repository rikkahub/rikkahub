package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.agent.AgentLoop
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.AgentRunRuntime
import me.rerere.rikkahub.data.ai.agent.NoOpAgentRunRuntime
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.permission.PermissionPolicy
import me.rerere.rikkahub.data.artifacts.ToolArtifactRunScope
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid
import me.rerere.workspace.Workspace

@kotlinx.serialization.Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

/**
 * 兼容门面：委托 [AgentLoop]，并保留翻译等旁路能力。
 */
class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val agentLoop: AgentLoop,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        describedTools: List<DescribedTool> = tools.map { DescribedTool(it, me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry.descriptorFor(it)) },
        workspace: Workspace? = null,
        runRuntime: AgentRunRuntime = NoOpAgentRunRuntime,
        artifactRunScope: ToolArtifactRunScope? = null,
        maxSteps: Int = 256,
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
        providerIdleTimeoutMillis: Long = 45_000L,
        defaultToolTimeoutMillis: Long = 30_000L,
    ): Flow<GenerationChunk> = agentLoop.run(
        settings = settings,
        model = model,
        messages = messages,
        inputTransformers = inputTransformers,
        outputTransformers = outputTransformers,
        assistant = assistant,
        memories = memories,
        tools = tools,
        describedTools = describedTools,
        workspace = workspace,
        runRuntime = runRuntime,
        artifactRunScope = artifactRunScope,
        maxSteps = maxSteps,
        processingStatus = processingStatus,
        conversationSystemPrompt = conversationSystemPrompt,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        workspaceCwd = workspaceCwd,
        mode = mode,
        permissionPolicy = permissionPolicy,
        isSubagentRun = isSubagentRun,
        allowParallelToolCalls = allowParallelToolCalls,
        useClientGeneratedToolExecutionIdentity = useClientGeneratedToolExecutionIdentity,
        providerIdleTimeoutMillis = providerIdleTimeoutMillis,
        defaultToolTimeoutMillis = defaultToolTimeoutMillis,
    )

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
