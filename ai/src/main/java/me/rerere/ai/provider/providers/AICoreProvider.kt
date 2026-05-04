package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AICoreReleaseStage
import me.rerere.ai.provider.AICORE_DEFAULT_MODELS
import me.rerere.ai.provider.AICORE_NANO_FAST_MODEL
import me.rerere.ai.provider.AICORE_NANO_FULL_MODEL
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "AICoreProvider"

/**
 * On-device LLM provider backed by Google's AICore (Gemini Nano) via the ML Kit GenAI prompt
 * API. Stateless — every inference call resolves a fresh GenerativeModel client and lets ML
 * Kit handle caching internally. The user-visible install state ("downloadable / downloading
 * / available / unavailable") is exposed via [checkStatus] and consumed by the settings UI.
 *
 * Tool-calling is not wired through yet; ML Kit GenAI 1.0.0-beta2 documents function calling
 * but the surface is in flux. First cut runs prompt-only inference; tools fall through to
 * being ignored.
 */
class AICoreProvider(private val context: Context) : Provider<ProviderSetting.AICore> {

    override suspend fun listModels(providerSetting: ProviderSetting.AICore): List<Model> =
        AICORE_DEFAULT_MODELS

    override suspend fun getBalance(providerSetting: ProviderSetting.AICore): String =
        "On-device — no API balance"

    override suspend fun streamText(
        providerSetting: ProviderSetting.AICore,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        val (preference, generativeModel) = openClient(providerSetting, params.model)
        try {
            val status: Int = generativeModel.checkStatus()
            if (status != FeatureStatus.AVAILABLE) {
                error(unavailableMessage(status))
            }
            generativeModel.warmup()

            val prompt = formatPromptFromMessages(messages)
            val temperature = (params.temperature ?: 0.7f).coerceIn(0f, 1f)
            val request = generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                params.topP?.let { /* topP not exposed in ML Kit GenAI prompt API */ }
            }

            var streamId = "aicore-${System.currentTimeMillis()}"
            generativeModel.generateContentStream(request).collect { response ->
                val candidate = response.candidates.firstOrNull()
                val deltaText = candidate?.text.orEmpty()
                val finishReason = candidate?.finishReason?.toString()
                emit(
                    MessageChunk(
                        id = streamId,
                        model = params.model.modelId,
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = if (deltaText.isNotEmpty()) {
                                        listOf(UIMessagePart.Text(deltaText))
                                    } else emptyList(),
                                ),
                                message = null,
                                finishReason = finishReason,
                            )
                        ),
                    )
                )
            }
        } finally {
            try { generativeModel.close() } catch (t: Throwable) {
                Log.w(TAG, "close failed", t)
            }
            // Mark the preference as used so the inline cache is consistent
            require(preference.isNotEmpty())
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.AICore,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val collected = StringBuilder()
        var finishReason: String? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            chunk.choices.firstOrNull()?.let { c ->
                c.delta?.parts?.forEach { p ->
                    if (p is UIMessagePart.Text) collected.append(p.text)
                }
                if (c.finishReason != null) finishReason = c.finishReason
            }
        }
        return MessageChunk(
            id = "aicore-${System.currentTimeMillis()}",
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(collected.toString())),
                    ),
                    finishReason = finishReason ?: "stop",
                )
            ),
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult = error("AICore does not support image generation")

    /**
     * Live status of the AICore feature on this device. Surfaced by the settings UI so the
     * user knows whether to enrol in the AICore beta, wait for a download, or move on.
     */
    /** Returns one of [FeatureStatus.AVAILABLE], DOWNLOADABLE, DOWNLOADING, UNAVAILABLE. */
    suspend fun checkStatus(providerSetting: ProviderSetting.AICore): Int {
        val (_, generativeModel) = openClient(
            providerSetting,
            providerSetting.models.firstOrNull() ?: AICORE_NANO_FAST_MODEL,
        )
        return try {
            generativeModel.checkStatus()
        } catch (t: Throwable) {
            Log.w(TAG, "checkStatus failed", t)
            FeatureStatus.UNAVAILABLE
        } finally {
            try { generativeModel.close() } catch (_: Throwable) {}
        }
    }

    /** Builds the ML Kit GenerativeModel client for [model] given the provider settings. */
    private fun openClient(
        providerSetting: ProviderSetting.AICore,
        model: Model,
    ): Pair<String, GenerativeModel> {
        val preference: Int =
            if (model.modelId == AICORE_NANO_FULL_MODEL.modelId) ModelPreference.FULL
            else ModelPreference.FAST
        val release: Int = when (providerSetting.releaseStage) {
            AICoreReleaseStage.PREVIEW -> ModelReleaseStage.PREVIEW
            AICoreReleaseStage.STABLE -> ModelReleaseStage.STABLE
        }
        val generativeModel = Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    this.preference = preference
                    this.releaseStage = release
                }
            }
        )
        return preference.toString() to generativeModel
    }

    private fun unavailableMessage(status: Int): String = when (status) {
        FeatureStatus.UNAVAILABLE ->
            "AICore is not available on this device. Pixel 8/9/10 with AICore beta required."
        FeatureStatus.DOWNLOADABLE ->
            "AICore model not downloaded yet. Open Settings → Providers → AICore → Prepare model."
        FeatureStatus.DOWNLOADING ->
            "AICore model is still downloading. Wait for the download to complete and retry."
        else -> "AICore is unavailable (status=$status)."
    }

    /**
     * Flattens the conversation into a single text prompt the ML Kit GenAI surface expects.
     * Mirrors the gallery reference implementation. Tool calls and image/audio parts are
     * collapsed to text since the first cut is text-only.
     */
    private fun formatPromptFromMessages(messages: List<UIMessage>): String = buildString {
        for (message in messages) {
            val role = when (message.role) {
                MessageRole.SYSTEM -> "system"
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "model"
                MessageRole.TOOL -> "tool"
            }
            val text = message.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            if (text.isNotBlank()) {
                append(role).append(": ").append(text).append('\n')
            }
        }
        append("model: ")
    }
}
