package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.service.GenerationProgress
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val ENGINE_TAG = "GenerationEngine"

@Serializable
sealed interface GenerationChunk {
    data class Messages(val messages: List<UIMessage>) : GenerationChunk
    data class AwaitingApproval(val toolCallIds: List<String>) : GenerationChunk
    data object Completed : GenerationChunk
    data object StepLimitReached : GenerationChunk
}

class GenerationEngine(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val providerExecutor: ProviderExecutor,
    private val toolCallExecutor: ToolCallExecutor,
) {
    fun generate(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        reportProgress: suspend (GenerationProgress?) -> Unit = {},
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
        var currentMessages = messages

        repeat(maxSteps) { step ->
            Log.i(ENGINE_TAG, "Starting model/tool step $step (${model.id})")
            val definitions = tools
            val resumableCalls = currentMessages.lastOrNull()?.getTools()
                .orEmpty()
                .filter(UIMessagePart.Tool::canResumeExecution)

            val callsToExecute = if (resumableCalls.isEmpty()) {
                providerExecutor.execute(
                    assistant = assistant,
                    settings = settings,
                    messages = currentMessages,
                    onUpdateMessages = { updated ->
                        currentMessages = updated.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                        )
                        emit(
                            GenerationChunk.Messages(
                                currentMessages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings,
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = definitions,
                    memories = memories.orEmpty(),
                    reportProgress = reportProgress,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                )
                currentMessages = currentMessages.visualTransforms(
                    outputTransformers,
                    context,
                    model,
                    assistant,
                    settings,
                ).onGenerationFinish(
                    outputTransformers,
                    context,
                    model,
                    assistant,
                    settings,
                )
                currentMessages = currentMessages.dropLast(1) + currentMessages.last().copy(
                    finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(currentMessages))

                val calls = currentMessages.last().getTools().filterNot(UIMessagePart.Tool::isExecuted)
                if (calls.isEmpty()) {
                    emit(GenerationChunk.Completed)
                    return@flow
                }
                val decision = toolCallExecutor.prepareApproval(calls, definitions)
                if (decision.tools != calls) {
                    currentMessages = replaceToolCalls(currentMessages, decision.tools)
                    emit(GenerationChunk.Messages(currentMessages))
                }
                if (decision.pendingIds.isNotEmpty()) {
                    emit(GenerationChunk.AwaitingApproval(decision.pendingIds))
                    return@flow
                }
                decision.tools
            } else {
                resumableCalls
            }

            val executed = toolCallExecutor.execute(callsToExecute, definitions)
            if (executed.isEmpty()) {
                val pending = currentMessages.lastOrNull()?.getTools().orEmpty()
                    .filter(UIMessagePart.Tool::isPending)
                    .map(UIMessagePart.Tool::toolCallId)
                emit(GenerationChunk.AwaitingApproval(pending))
                return@flow
            }
            currentMessages = replaceToolCalls(currentMessages, executed)
            emit(
                GenerationChunk.Messages(
                    currentMessages.transforms(
                        outputTransformers,
                        context,
                        model,
                        assistant,
                        settings,
                    )
                )
            )
        }
        emit(GenerationChunk.StepLimitReached)
    }.flowOn(Dispatchers.IO)

    private fun replaceToolCalls(
        messages: List<UIMessage>,
        replacements: List<UIMessagePart.Tool>,
    ): List<UIMessage> {
        val last = messages.last()
        return messages.dropLast(1) + last.copy(
            parts = last.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    replacements.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
        )
    }
}
