package me.rerere.locallm.litert

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.MessageRole
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
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.LocalRuntimePreferences

/**
 * Implements the existing Provider interface so any assistant can pick a LiteRT
 * model the same way it picks an OpenAI / Claude model. Routes inference through
 * [LiteRtRuntime] and tool calls through [LiteRtToolPrefix].
 */
class LiteRtProvider(
    private val runtime: LiteRtRuntime,
    private val prefs: LocalRuntimePreferences,
) : Provider<ProviderSetting.LiteRtLocal> {

    override suspend fun listModels(providerSetting: ProviderSetting.LiteRtLocal): List<Model> {
        val installed = prefs.installedModels(LocalRuntime.LiteRT)
        return installed.map { (fileName, _) ->
            Model(
                modelId = fileName,
                displayName = fileName,
            )
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.LiteRtLocal,
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
            id = "litert-${System.currentTimeMillis()}",
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

    override suspend fun streamText(
        providerSetting: ProviderSetting.LiteRtLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        val installed = prefs.installedModels(LocalRuntime.LiteRT)
        val modelPath = installed[params.model.modelId]
            ?: throw IllegalStateException("Model ${params.model.modelId} not installed")

        val toolPrefix = LiteRtToolPrefix.buildPrefix(params.tools)
        val systemTexts = messages
            .filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n") { msg ->
                msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            }
        val convoText = messages.filter { it.role != MessageRole.SYSTEM }.joinToString("\n") { msg ->
            val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            when (msg.role) {
                MessageRole.USER -> "User: $text"
                MessageRole.ASSISTANT -> "Assistant: $text"
                else -> text
            }
        }
        val prompt = buildString {
            if (toolPrefix.isNotEmpty()) appendLine(toolPrefix)
            if (systemTexts.isNotEmpty()) appendLine(systemTexts)
            append(convoText)
            append("\nAssistant: ")
        }

        val streamId = "litert-${System.currentTimeMillis()}"
        runtime.streamGenerate(prompt, modelPath = modelPath).collect { partial ->
            emit(
                MessageChunk(
                    id = streamId,
                    model = params.model.modelId,
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Text(partial)),
                            ),
                            message = null,
                            finishReason = null,
                        )
                    ),
                )
            )
        }
        // Emit the terminal chunk with finishReason = "stop"
        emit(
            MessageChunk(
                id = streamId,
                model = params.model.modelId,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                        ),
                        message = null,
                        finishReason = "stop",
                    )
                ),
            )
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult = error("LiteRT does not support image generation in 22A")
}
