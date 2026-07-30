package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging

fun interface TokenUsageRecorder {
    suspend fun record(usage: TokenUsage)
}

internal class UsageTrackingProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
    private val recorder: TokenUsageRecorder,
) : Provider<T> by delegate {
    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        return delegate.generateText(providerSetting, messages, params).also { chunk ->
            chunk.usage?.recordSafely()
        }
    }

    override suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        var requestUsage: TokenUsage? = null
        delegate.streamText(providerSetting, messages, params).collect { chunk ->
            chunk.usage?.let {
                requestUsage = requestUsage.merge(it)
            }
            emit(chunk)
        }
        requestUsage?.recordSafely()
    }

    private suspend fun TokenUsage.recordSafely() {
        if (promptTokens == 0 && completionTokens == 0 && cachedTokens == 0) return

        try {
            recorder.record(this)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            Logging.log(TAG, "Failed to record token usage: ${exception.stackTraceToString()}")
        }
    }

    private companion object {
        const val TAG = "UsageTrackingProvider"
    }
}
