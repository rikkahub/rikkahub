package me.rerere.locallm.litert

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

private const val TAG = "LiteRtProvider"

/**
 * Implements the existing Provider interface so any assistant can pick a LiteRT
 * model the same way it picks an OpenAI / Claude model. Routes inference through
 * [LiteRtRuntime] and tool calls through [LiteRtToolPrefix].
 *
 * When [LiteRtRuntime.ensureLoaded] throws [LiteRtModelCorruptException], this provider
 * self-heals: the broken file is deleted from disk, removed from [LocalRuntimePreferences],
 * and removed from [ProviderSetting.LiteRtLocal.models] via [settingsUpdater]. The caller
 * receives a user-readable [RuntimeException] explaining what happened.
 *
 * @param settingsUpdater Suspend lambda that lets the provider mutate the persisted
 *   [ProviderSetting] list without coupling the `local-llm` module to `SettingsStore`
 *   directly. Supply `{ fn -> settingsStore.update { old -> old.copy(providers = fn(old.providers)) } }`
 *   from the DI layer.
 */
class LiteRtProvider(
    private val runtime: LiteRtRuntime,
    private val prefs: LocalRuntimePreferences,
    private val settingsUpdater: suspend (transform: (List<ProviderSetting>) -> List<ProviderSetting>) -> Unit,
) : Provider<ProviderSetting.LiteRtLocal> {

    /**
     * Self-heal a permanently corrupt model: delete the file from disk, remove it from
     * [LocalRuntimePreferences], and remove it from the provider's models list in settings.
     * After cleanup, throws a user-readable [RuntimeException] so the chat surface can
     * display an appropriate error message.
     */
    private suspend fun handleCorruptModel(corrupt: LiteRtModelCorruptException): Nothing {
        val file = java.io.File(corrupt.modelPath)
        val fileName = file.name
        Log.w(TAG, "Model file is corrupt — auto-removing: $fileName (${corrupt.modelPath})")

        // 1. Delete the file from disk (best-effort; log if it fails).
        runCatching { file.delete() }.onFailure { e ->
            Log.e(TAG, "Failed to delete corrupt model file: ${corrupt.modelPath}", e)
        }

        // 2. Remove from the installed-models DataStore index.
        runCatching { prefs.removeInstalledModel(LocalRuntime.LiteRT, fileName) }.onFailure { e ->
            Log.e(TAG, "Failed to remove model from prefs: $fileName", e)
        }

        // 3. Remove the matching Model entry from ProviderSetting.LiteRtLocal.models so the
        //    entry disappears from the chat picker without requiring a manual delete.
        runCatching {
            settingsUpdater { providers ->
                providers.map { p ->
                    if (p is ProviderSetting.LiteRtLocal) {
                        val target = p.models.firstOrNull { it.modelId == fileName }
                        if (target != null) p.delModel(target) else p
                    } else {
                        p
                    }
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to remove model from provider settings: $fileName", e)
        }

        throw RuntimeException(
            "The model file \"$fileName\" could not be loaded because it is corrupt or " +
            "incompatible with this runtime version. The file has been removed automatically. " +
            "Pick a different model in Settings → Providers → Local · LiteRT, or tap " +
            "\"Install default\" to download the recommended model.",
            corrupt,
        )
    }

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

        // Guard against a stale DataStore entry (file registered but later deleted by the user,
        // ADB, or a failed partial download).  Surface a clean message rather than letting the
        // engine throw an opaque native error.
        if (!java.io.File(modelPath).exists()) {
            throw IllegalStateException(
                "Model file for \"${params.model.modelId}\" is no longer present on disk " +
                "($modelPath). Delete the model entry in Settings → Providers → Local · LiteRT " +
                "and re-download it."
            )
        }

        // Pass the cached accelerator so ensureLoaded does not re-probe on every turn.
        // Probe runs once at install/re-detect time and is persisted; reading it here
        // avoids the System.loadLibrary("qnn_delegate_jni") call on every generation.
        val cachedAccel = prefs.acceleratorFlow(LocalRuntime.LiteRT).first()

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

        // New LiteRT-LM pattern: ensure model+conversation are loaded before streaming.
        // Pass cachedAccel (may be null on very first run) so we avoid re-probing every turn.
        // If the model file is structurally broken, self-heal and surface a user-readable error.
        try {
            runtime.ensureLoaded(modelPath, preferredAccel = cachedAccel)
        } catch (corrupt: LiteRtModelCorruptException) {
            handleCorruptModel(corrupt)
        }

        val streamId = "litert-${System.currentTimeMillis()}"
        runtime.streamGenerate(prompt).collect { partial ->
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
