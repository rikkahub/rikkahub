package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.service.GenerationProgress
import me.rerere.rikkahub.service.NetworkFailureKind
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.uuid.Uuid

private const val PROVIDER_EXECUTOR_TAG = "ProviderExecutor"
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

class ProviderExecutor(
    private val context: Context,
) {
    suspend fun execute(
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
        reportProgress: suspend (GenerationProgress?) -> Unit,
        conversationSystemPrompt: String?,
        conversationId: Uuid?,
        conversationModeInjectionIds: Set<Uuid>,
        conversationLorebookIds: Set<Uuid>,
        workspaceCwd: String?,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectivePrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectivePrompt.isNotBlank()) append(effectivePrompt)
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories))
                }
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(system).copy(isSynthetic = true))
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            reportProgress = reportProgress,
            workspaceCwd = workspaceCwd,
        )

        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = assistant.customHeaders + model.customHeaders,
            customBody = assistant.customBodies + model.customBodies,
            sessionId = conversationId?.toString(),
        )

        try {
            if (assistant.streamOutput) {
                executeStreaming(
                    model,
                    providerImpl,
                    provider,
                    internalMessages,
                    messages,
                    params,
                    reportProgress,
                    onUpdateMessages,
                )
            } else {
                val result = executeWithRetry(reportProgress) {
                    providerImpl.generateText(provider, internalMessages, params)
                }
                onUpdateMessages(messages.handleTextGenerationResult(result, model))
            }
        } finally {
            reportProgress(null)
        }
    }

    private suspend fun executeStreaming(
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        internalMessages: List<UIMessage>,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        reportProgress: suspend (GenerationProgress?) -> Unit,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
    ) {
        val responseBase = if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
            messages
        } else {
            messages + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
                modelId = model.id,
            )
        }
        var retries = 0
        while (true) {
            val handler = StreamChunkHandler(model)
            var attemptMessages = responseBase
            try {
                providerImpl.streamText(provider, internalMessages, params).collect { chunk ->
                    try {
                        if (retries > 0) reportProgress(null)
                        attemptMessages = handler.handle(attemptMessages, chunk)
                        onUpdateMessages(attemptMessages)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        throw StreamChunkHandlingException(error)
                    }
                }
                return
            } catch (error: Throwable) {
                if (error is StreamChunkHandlingException) throw error.cause ?: error
                retries = awaitRetryOrThrow(error, retries, reportProgress)
            }
        }
    }

    private suspend fun <T> executeWithRetry(
        reportProgress: suspend (GenerationProgress?) -> Unit,
        block: suspend () -> T,
    ): T {
        var retries = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retries = awaitRetryOrThrow(error, retries, reportProgress)
            }
        }
    }

    private suspend fun awaitRetryOrThrow(
        error: Throwable,
        retries: Int,
        reportProgress: suspend (GenerationProgress?) -> Unit,
    ): Int {
        currentCoroutineContext().ensureActive()
        if (error !is IOException || retries >= MAX_PROVIDER_NETWORK_RETRIES) throw error
        val attempt = retries + 1
        val delayMs = INITIAL_PROVIDER_RETRY_DELAY_MS shl retries
        reportProgress(
            GenerationProgress.NetworkRetry(classifyNetworkFailure(error), attempt, MAX_PROVIDER_NETWORK_RETRIES)
        )
        Log.w(PROVIDER_EXECUTOR_TAG, "Network retry $attempt/$MAX_PROVIDER_NETWORK_RETRIES", error)
        delay(delayMs)
        return attempt
    }

    private fun classifyNetworkFailure(error: IOException): NetworkFailureKind = when (error) {
        is UnknownHostException -> NetworkFailureKind.UnknownHost
        is SocketTimeoutException -> NetworkFailureKind.Timeout
        is ConnectException, is NoRouteToHostException -> NetworkFailureKind.Unreachable
        else -> NetworkFailureKind.Disconnected
    }
}
