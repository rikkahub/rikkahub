package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import kotlin.uuid.Uuid

private const val TAG = "UsageRecorder"

/** 用量从哪类请求来，按名称存进 usage_record.source */
enum class UsageSource {
    CHAT,
    COMPRESSION,
    TITLE,
    SUGGESTION,
    TRANSLATION,
    MEMORY,
    OCR,
}

/** 记录用量时附带的归属信息 */
data class UsageTag(
    val source: UsageSource,
    val assistantId: Uuid? = null,
    val conversationId: Uuid? = null,
)

/**
 * 把每次模型请求的 usage 写进 usage_record。
 *
 * 发起请求的地方用 [wrap] 包一层 provider，每次 generateText / streamText 结束时记一行；
 * 重试的每次尝试都是独立的请求，各记各的。写库在 [scope] 里异步进行，失败只打日志，不影响请求本身。
 */
class UsageRecorder(
    private val dao: UsageRecordDAO,
    private val scope: CoroutineScope,
) {
    fun <T : ProviderSetting> wrap(provider: Provider<T>, tag: UsageTag): Provider<T> =
        UsageRecordingProvider(provider, tag)

    private fun record(tag: UsageTag, providerSetting: ProviderSetting, params: TextGenerationParams, usage: TokenUsage) {
        if (usage.promptTokens <= 0 && usage.completionTokens <= 0) return
        val record = UsageRecordEntity(
            createdAt = System.currentTimeMillis(),
            source = tag.source.name,
            providerId = providerSetting.id.toString(),
            modelId = params.model.id.toString(),
            modelName = params.model.modelId,
            assistantId = tag.assistantId?.toString(),
            conversationId = tag.conversationId?.toString(),
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cachedTokens = usage.cachedTokens,
        )
        // Room 的 suspend 写入自己会切到后台线程
        scope.launch {
            runCatching { dao.insert(record) }
                .onFailure { Log.w(TAG, "Failed to record usage", it) }
        }
    }

    private inner class UsageRecordingProvider<T : ProviderSetting>(
        private val delegate: Provider<T>,
        private val tag: UsageTag,
    ) : Provider<T> by delegate {
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = delegate.generateText(providerSetting, messages, params).also { result ->
            result.usage?.let { record(tag, providerSetting, params, it) }
        }

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            val upstream = delegate.streamText(providerSetting, messages, params)
            return flow {
                // 同一次请求里的 usage 是逐步更新的快照，按 merge 合并；中途取消或出错时已经产生的用量也要记下
                var usage: TokenUsage? = null
                try {
                    upstream.collect { chunk ->
                        if (chunk is StreamChunk.Usage) usage = usage.merge(chunk.usage)
                        emit(chunk)
                    }
                } finally {
                    usage?.let { record(tag, providerSetting, params, it) }
                }
            }
        }
    }
}
