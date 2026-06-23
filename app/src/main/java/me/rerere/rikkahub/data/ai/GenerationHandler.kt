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
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.runtime.ChatTurnRuntime
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.contract.ConversationReader
import me.rerere.ai.runtime.contract.ModelProviderResolver
import me.rerere.ai.runtime.contract.MemoryWriter
import me.rerere.ai.runtime.contract.RecalledMemory
import me.rerere.ai.runtime.contract.RuntimeClock
import me.rerere.ai.runtime.contract.RuntimeGenerationLog
import me.rerere.ai.runtime.contract.RuntimeLogSink
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.contract.TurnMessageTransforms
import me.rerere.ai.runtime.hooks.HookDispatcher
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.common.text.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * App-side composition wrapper around the neutral [ChatTurnRuntime] (issue #243 slice 9).
 *
 * `generateText` keeps its app-typed signature (callers pass `Settings`/`Assistant`/transformers) so
 * no caller changes; it maps those onto the runtime's neutral [TurnConfig]/`AssistantConfig`, binds
 * the transformer pipeline + AI-request log behind functional seams (the pipeline still holds the
 * Android `Context`/`Settings`/`Assistant` here, on the app side), and delegates the turn to
 * [ChatTurnRuntime.run]. `translateText` stays app-side (a separate use-case, §C NON-GOAL).
 */
class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryWriter: MemoryWriter,
    private val conversationReader: ConversationReader,
    private val modelProviderResolver: ModelProviderResolver,
    private val clock: RuntimeClock,
    private val logSink: RuntimeLogSink,
    private val aiLoggingManager: AILoggingManager,
    // Required, not defaulted: a default of null is how the PreToolUse fire-point silently became
    // dead code in production (#200 review finding 1) — the composition root must decide.
    private val hookDispatcher: HookDispatcher,
) {
    private val chatTurnRuntime = ChatTurnRuntime(
        json = json,
        resolver = modelProviderResolver,
        memoryWriter = memoryWriter,
        conversationReader = conversationReader,
        clock = clock,
        logSink = logSink,
        generationLog = RuntimeGenerationLog { messages, params, provider, stream ->
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = stream,
                )
            )
        },
        hookDispatcher = hookDispatcher,
    )

    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<RecalledMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        // The active `/goal` condition, surfaced into the system prompt every turn (ungated). Null = none.
        activeGoal: String? = null,
    ): Flow<GenerationChunk> {
        // The transformer pipeline still holds Android context/settings/assistant on the app side;
        // bind it behind the neutral seam, capturing those here so the runtime never sees them. The
        // input pipeline threads the conversation injection ids + processingStatus exactly as the
        // pre-slice generateInternal did; the output/visual/finish pipelines do not.
        val transforms = object : TurnMessageTransforms {
            override suspend fun transformInput(messages: List<UIMessage>): List<UIMessage> =
                messages.transforms(
                    transformers = inputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    processingStatus = processingStatus,
                )

            override suspend fun transformOutput(messages: List<UIMessage>): List<UIMessage> =
                messages.transforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                )

            override suspend fun visualTransform(messages: List<UIMessage>): List<UIMessage> =
                messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                )

            override suspend fun onGenerationFinish(messages: List<UIMessage>): List<UIMessage> =
                messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings,
                )
        }

        return chatTurnRuntime.run(
            turn = TurnConfig(
                defaultModelId = settings.chatModelId,
                providers = settings.providers,
                assistants = settings.assistants.map { it.toAssistantConfig() },
            ),
            model = model,
            messages = messages,
            assistant = assistant.toAssistantConfig(),
            transforms = transforms,
            memories = memories,
            tools = tools,
            maxSteps = maxSteps,
            conversationSystemPrompt = conversationSystemPrompt,
            activeGoal = activeGoal,
        )
    }

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
            // Use regular translation with prompt
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
            // Use Qwen MT model with special translation options
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
