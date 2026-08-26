package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationEngine
import me.rerere.rikkahub.data.ai.GenerationPlanFactory
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Conversation
import java.io.IOException
import java.time.Instant
import kotlin.uuid.Uuid

private const val COORDINATOR_TAG = "ConversationGeneration"

class ConversationGenerationCoordinator(
    private val context: Application,
    private val appScope: AppScope,
    private val runtimeStore: ConversationRuntimeStore,
    private val planFactory: GenerationPlanFactory,
    private val engine: GenerationEngine,
    private val postProcessor: ConversationPostProcessor,
    private val appEventBus: AppEventBus,
) {
    suspend fun start(handle: GenerationHandle, messageEndExclusive: Int? = null) {
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            runGeneration(handle, messageEndExclusive)
        }
        runtimeStore.registerJob(handle.conversationId, handle.generationId, job)
        job.start()
    }

    private suspend fun runGeneration(handle: GenerationHandle, messageEndExclusive: Int?) {
        val conversationId = handle.conversationId
        val generationId = handle.generationId
        var foregroundStarted = false
        var senderName = "Assistant"
        var terminalPreview: String? = null

        try {
            runtimeStore.updateGeneration(conversationId, generationId) {
                GenerationState.Running(generationId)
            }
            foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )

            val prepared = runtimeStore.mutate(conversationId) { conversation ->
                sanitizeConversation(conversation).copy(chatSuggestions = emptyList())
            }.conversation
            val plan = planFactory.create(prepared)
            val configuration = plan.configuration
            senderName = configuration.assistant.name.ifBlank { configuration.model.displayName }
            val messages = prepared.currentMessages.let { current ->
                messageEndExclusive?.let { current.take(it.coerceIn(0, current.size)) } ?: current
            }

            engine.generate(
                settings = configuration.settings,
                model = configuration.model,
                messages = messages,
                inputTransformers = plan.inputTransformers,
                outputTransformers = plan.outputTransformers,
                assistant = configuration.assistant,
                memories = plan.memories,
                tools = plan.tools,
                reportProgress = { progress ->
                    runtimeStore.updateGeneration(conversationId, generationId) {
                        GenerationState.Running(generationId, progress)
                    }
                },
                conversationSystemPrompt = prepared.customSystemPrompt,
                conversationId = conversationId,
                conversationModeInjectionIds = prepared.modeInjectionIds,
                conversationLorebookIds = prepared.lorebookIds,
                workspaceCwd = prepared.workspaceCwd,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        runtimeStore.mutate(conversationId) { latest ->
                            latest.updateCurrentMessages(chunk.messages)
                        }
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(
                                    conversationId = conversationId,
                                    generationId = generationId,
                                    lastMessage = lastMessage,
                                    senderName = senderName,
                                )
                            )
                        }
                    }

                    is GenerationChunk.AwaitingApproval -> {
                        runtimeStore.updateGeneration(
                            conversationId = conversationId,
                            expectedGenerationId = generationId,
                            persistConversation = true,
                        ) {
                            GenerationState.AwaitingApproval(generationId, chunk.toolCallIds)
                        }
                    }

                    GenerationChunk.Completed -> {
                        val completed = finishGenerationMessages(conversationId)
                        terminalPreview = completed.currentMessages.lastOrNull()?.toText()?.trim()?.take(50)
                        runtimeStore.updateGeneration(
                            conversationId = conversationId,
                            expectedGenerationId = generationId,
                            persistConversation = true,
                        ) { GenerationState.Completed(generationId) }
                        launchPostProcessing(conversationId, generationId)
                    }

                    GenerationChunk.StepLimitReached -> throw ChatCommandException(
                        ChatFailure(
                            code = ChatFailureCode.StepLimit,
                            message = "Generation exceeded the maximum number of tool steps",
                            retryable = false,
                            conversationId = conversationId,
                            generationId = generationId,
                        )
                    )
                }
            }
        } catch (error: CancellationException) {
            finishCancelledMessages(conversationId)
            runCatching {
                runtimeStore.updateGeneration(
                    conversationId,
                    generationId,
                    persistConversation = true,
                ) { GenerationState.Cancelled(generationId) }
            }
            throw error
        } catch (error: Throwable) {
            val failure = error.toChatFailure(conversationId, generationId)
            Log.e(COORDINATOR_TAG, "Generation failed: $conversationId/$generationId", error)
            runCatching { finishGenerationMessages(conversationId) }
            runCatching {
                runtimeStore.updateGeneration(
                    conversationId,
                    generationId,
                    persistConversation = true,
                ) { GenerationState.Failed(generationId, failure) }
            }
            runtimeStore.emitFailure(conversationId, failure)
        } finally {
            if (foregroundStarted) {
                ChatGenerationForegroundService.release(context, generationId)
            }
            appEventBus.tryEmit(
                AppEvent.ChatGenerationEnded(
                    conversationId = conversationId,
                    generationId = generationId,
                    senderName = senderName,
                    contentPreview = terminalPreview,
                )
            )
        }
    }

    private suspend fun finishGenerationMessages(conversationId: Uuid): Conversation {
        return runtimeStore.mutate(conversationId) { conversation ->
            conversation.copy(
                messageNodes = conversation.messageNodes.map { node ->
                    node.copy(messages = node.messages.map(UIMessage::finishReasoning))
                },
                updateAt = Instant.now(),
            )
        }.conversation
    }

    private suspend fun finishCancelledMessages(conversationId: Uuid) {
        runtimeStore.mutate(conversationId) { conversation ->
            conversation.copy(
                messageNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { message ->
                            message.finishPendingTools(::cancelToolByUser).finishReasoning()
                        }
                    )
                },
                updateAt = Instant.now(),
            )
        }
    }

    private fun launchPostProcessing(conversationId: Uuid, generationId: Uuid) {
        appScope.launch {
            runPostProcess(conversationId, generationId, "title") {
                postProcessor.generateTitle(conversationId)
            }
        }
        appScope.launch {
            runPostProcess(conversationId, generationId, "suggestions") {
                postProcessor.generateSuggestions(conversationId)
            }
        }
    }

    private suspend fun runPostProcess(
        conversationId: Uuid,
        generationId: Uuid,
        operation: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(COORDINATOR_TAG, "$operation post-processing failed", error)
            runtimeStore.emitFailure(
                conversationId,
                ChatFailure(
                    code = ChatFailureCode.PostProcessing,
                    message = "$operation post-processing failed",
                    retryable = true,
                    conversationId = conversationId,
                    generationId = generationId,
                )
            )
        }
    }

    private fun sanitizeConversation(conversation: Conversation): Conversation {
        val nodes = conversation.messageNodes.mapNotNull { node ->
            val current = node.currentMessage
            val pending = current.getTools().filterNot { it.isExecuted }
            if (pending.isEmpty() || pending.any { it.canResumeExecution }) return@mapNotNull node
            val messages = node.messages.filterNot { it.id == current.id }
            if (messages.isEmpty()) null else node.copy(
                messages = messages,
                selectIndex = node.selectIndex.coerceAtMost(messages.lastIndex),
            )
        }
        return conversation.copy(messageNodes = nodes)
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"cancelled","error":"Generation cancelled by user."}"""
            )
        ),
        approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
    )

    private fun Throwable.toChatFailure(conversationId: Uuid, generationId: Uuid): ChatFailure {
        if (this is ChatCommandException) {
            return failure.copy(conversationId = conversationId, generationId = generationId)
        }
        return ChatFailure(
            code = if (this is IOException) ChatFailureCode.Network else ChatFailureCode.Internal,
            message = if (this is IOException) "Network request failed" else "Generation failed",
            retryable = this is IOException,
            conversationId = conversationId,
            generationId = generationId,
        )
    }
}
