package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

class TranslationService(
    private val providerManager: ProviderManager,
) {
    fun translate(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
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
            val chunkHandler = StreamChunkHandler(model)

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = chunkHandler.handle(messages, chunk)
                messages.lastOrNull()?.toText()?.takeIf(String::isNotBlank)?.let { emit(it) }
            }
        } else {
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(sourceText)),
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
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH)),
                                )
                            },
                        )
                    ),
                ),
            )
            result.message.toText().takeIf(String::isNotBlank)?.let { emit(it) }
        }
    }.flowOn(Dispatchers.IO)
}
