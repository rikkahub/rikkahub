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
        // Honor the per-runtime force-CPU override. Default true after the LiteRT-LM
        // 0.11.0 GPU/NNAPI native crashes; users opt back in via the settings toggle.
        val forceCpu = prefs.forceCpu(LocalRuntime.LiteRT)

        // Per-model defaults curated to mirror Gallery's `model_allowlists/1_0_13.json`
        // (topK / topP / temperature / maxTokens / multimodal flags / speculative decoding).
        // For HF-pasted models that aren't in the table, this returns the FALLBACK config.
        val config = LiteRtModelDefaults.forModelFile(params.model.modelId)
        // Pre-flight: multimodal models REQUIRE a GPU vision backend (Gallery's mandate
        // — see LiteRtRuntime.tryLoadWithBackend's visionBackend logic). With Force CPU
        // on, the runtime would still configure visionBackend=GPU.Backend, the SDK's
        // vision executor would fail on init, the fallback to CPU would also fail (still
        // GPU vision needed), and the user would see the generic "engine could not load"
        // error. Refuse upfront with a clear, actionable message instead.
        if (config.supportsImage && forceCpu) {
            throw IllegalStateException(
                "Model \"${params.model.modelId}\" is multimodal (vision support) and " +
                    "requires the GPU backend, but \"Try GPU acceleration\" is currently " +
                    "off in Settings → Local · LiteRT. Either enable GPU and retry, or " +
                    "switch to a text-only model (Gemma3-1B-IT or Qwen2.5-1.5B-Instruct) " +
                    "which runs fine on CPU."
            )
        }
        // User-set max-context override. Lets capable models (Gemma 4 E2B = 32k) use more
        // than Gallery's curated default; the underlying KV cache size still caps it
        // (Qwen `ekv4096` cannot exceed 4096 regardless of this setting).
        val maxNumTokensOverride = prefs.maxNumTokensOverride(LocalRuntime.LiteRT)
        val effectiveMaxNumTokens = maxNumTokensOverride ?: config.maxTokens

        // ---- System instruction (RADICALLY TRIMMED for small-context local models) ----
        //
        // Cloud providers happily accept full system prompts: agent-core skill body
        // (~3-5k tokens of persona + tool docs) + every tool's JSON schema (~5-10k more)
        // fits comfortably inside a 32k–200k context window. LiteRT models max out at
        // 1-32k TOTAL, often 4k. A fresh "hi" against the default Qwen 4k model SDK-aborts
        // with "Input token ids are too long: 18031 >= 4096" because we forwarded the same
        // bulky system instruction the cloud path uses.
        //
        // For LiteRT we therefore:
        //  - Use [buildCompactPrefix] (one-line `- name: desc` per tool, NO schemas).
        //  - Drop bulky auto-loaded skill bodies — keep at most the first
        //    [SYSTEM_MESSAGE_CHAR_BUDGET] chars of system text (room for the user's
        //    custom system prompt + a short identity line, not for skill prose).
        //
        // A 1.5B model can't usefully consume agent-core's persona docs anyway — the
        // model lacks the capacity to follow that level of instruction.
        val systemTextsRaw = messages
            .filter { it.role == MessageRole.SYSTEM }
            .map { msg ->
                msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val trimmedSystemTexts = if (systemTextsRaw.length > SYSTEM_MESSAGE_CHAR_BUDGET) {
            systemTextsRaw.take(SYSTEM_MESSAGE_CHAR_BUDGET) + "\n…(truncated for local model context)"
        } else {
            systemTextsRaw
        }

        // Compact tool prefix only — full-schema dump would re-blow the context budget
        // (every tool's schema is ~200-500 chars, ×50 tools = ~15k chars on its own).
        // Caps further: at most 25 tools, at most 2000 chars total. Prefill on CPU is
        // ~1-3 tok/s; every tool we shave off knocks ~30s off "time to first token".
        val toolPrefix = LiteRtToolPrefix.buildCompactPrefix(
            params.tools,
            maxTools = MAX_TOOLS_IN_PREFIX,
            maxChars = TOOL_PREFIX_CHAR_BUDGET,
        )

        val combinedSystem = buildString {
            if (toolPrefix.isNotEmpty()) {
                append(toolPrefix.trimEnd())
                append("\n\n")
            }
            if (trimmedSystemTexts.isNotEmpty()) append(trimmedSystemTexts)
        }.trim()

        // ---- Conversation history → turn list + cold-path blob ----
        //
        // We hand the runtime BOTH:
        //   - `turns`: the full (trimmed) turn list, each carrying a content signature. The
        //     runtime uses these to decide whether its warm Conversation's KV cache already
        //     holds a prefix of this history — if so it sends ONLY the new turn and reuses
        //     the cache (Gallery's behaviour), instead of re-prefilling the whole history.
        //   - `coldBlob`: the full render used only when the cache can't be reused (first
        //     turn, edited/regenerated history, tool round-trip, config change). Always
        //     correct, just not warm.
        //
        // TOOL-role messages are dropped here: their content is already represented inline
        // in the immediately-preceding ASSISTANT message's Tool part output.
        //
        // Trimming: drop the oldest turns one at a time until the cold render fits under
        // HISTORY_CHAR_BUDGET. A trimmed list is no longer a forward-only extension of what
        // the Conversation already processed, so the runtime correctly falls back to a cold
        // rebuild for that turn — exactly the behaviour we want when history is reshaped.
        var trimmed = messages.filter {
            it.role != MessageRole.SYSTEM && it.role != MessageRole.TOOL
        }
        var coldBlob = renderColdBlob(trimmed)
        while (coldBlob.length > HISTORY_CHAR_BUDGET && trimmed.size > 1) {
            trimmed = trimmed.drop(1)
            coldBlob = renderColdBlob(trimmed)
        }
        val turns = trimmed.map { it.toTurn() }

        // ---- Engine load with full per-model + per-call config ----
        val resolvedAccel = try {
            runtime.ensureLoaded(
                modelPath = modelPath,
                preferredAccel = cachedAccel,
                forceCpu = forceCpu,
                maxNumTokens = effectiveMaxNumTokens,
                supportImage = config.supportsImage,
                supportAudio = config.supportsAudio,
                speculativeDecoding = config.supportsSpeculativeDecoding,
                systemInstructionText = combinedSystem.ifBlank { null },
                tools = emptyList(), // Native tool registration deferred; we use prompt-engineered tools.
                constrainedDecoding = false, // Future upgrade.
                topK = config.topK,
                topP = config.topP,
                temperature = config.temperature,
            )
        } catch (corrupt: LiteRtModelCorruptException) {
            handleCorruptModel(corrupt)
        }
        // Persist whatever the runtime actually used. Without this, the in-runtime
        // GPU -> CPU fallback only sticks for the current process: every cold start
        // re-probes the (known-broken-on-this-device) GPU and wastes ~1 s before
        // landing on CPU again. By persisting we also surface the truth to the
        // Doctor and Settings UI ("running on CPU because GPU failed at init").
        if (resolvedAccel != cachedAccel) {
            runCatching { prefs.setAccelerator(LocalRuntime.LiteRT, resolvedAccel) }
                .onFailure { Log.w(TAG, "setAccelerator($resolvedAccel) failed", it) }
        }

        // ---- Stream + cumulative→delta conversion ----
        //
        // The SDK's MessageCallback.onMessage emits the CUMULATIVE response so far
        // (Gallery's `partialResult` is consumed via a REPLACE-the-content path). But
        // RikkaHub's downstream chunk-handling APPENDS each Text part (see
        // UIMessage.appendChunk in ai/ui/Message.kt: incoming Text parts are concatenated
        // onto the previous Text part). Emitting cumulative chunks straight through would
        // duplicate the response geometrically. So we hold the previous cumulative and
        // emit only the new suffix as the delta.
        //
        // We also collect the full cumulative response so we can extract tool-call blocks
        // at end-of-stream (see the comment block right after the collect{} below for why
        // we do this once at the end rather than incrementally like AICoreProvider does).
        val streamId = "litert-${System.currentTimeMillis()}"
        var previousCumulative = ""
        val fullResponseBuilder = StringBuilder()

        try {
            runtime.streamTurns(history = turns, coldBlob = coldBlob).collect { cumulative ->
                // Defensive: if the SDK ever emits a non-monotonic cumulative (e.g. after
                // an internal retry or template re-tokenisation), treat the new payload as a
                // fresh start — emit it whole as the delta rather than computing a negative-
                // length suffix that would silently drop characters.
                val delta = if (cumulative.startsWith(previousCumulative)) {
                    cumulative.substring(previousCumulative.length)
                } else {
                    cumulative
                }
                previousCumulative = cumulative
                fullResponseBuilder.setLength(0)
                fullResponseBuilder.append(cumulative)

                if (delta.isNotEmpty()) {
                    emit(
                        MessageChunk(
                            id = streamId,
                            model = params.model.modelId,
                            choices = listOf(
                                UIMessageChoice(
                                    index = 0,
                                    delta = UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listOf(UIMessagePart.Text(delta)),
                                    ),
                                    message = null,
                                    finishReason = null,
                                )
                            ),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            // Translate the LiteRT-LM SDK's raw native-error text into something the user
            // and the chat error surface can act on. The most common one — "Input token
            // ids are too long. Exceeding the maximum number of tokens allowed: N >= M"
            // — looks scary but means "your conversation is bigger than this model can
            // hold". Surface that with a recovery hint.
            throw translateSdkError(t, effectiveMaxNumTokens)
        }

        // ---- Tool-call extraction at end-of-stream ----
        //
        // AICoreProvider does this incrementally with ToolTagParser.feed() so the visible
        // text never momentarily contains the raw <tool_call> block. We do it once at end
        // of stream because (a) LiteRT's onMessage gives cumulative text, not deltas, so a
        // streaming parser would have to constantly diff and re-scan the same prefix, and
        // (b) the visible-text leak window is small and the alternative is significantly
        // more state to maintain. If a tool call is detected, we emit a corrective chunk
        // that adds the Tool part AND signals finishReason="tool_calls" so the
        // GenerationHandler dispatches the tool and resumes generation on the next turn.
        //
        // Note: the raw <tool_call>...</tool_call> text already streamed to the user.
        // Stripping it from the visible text in retrospect is non-trivial without the
        // chunk protocol supporting "remove last N characters", so we leave it visible —
        // the appended Tool part below is what makes the behaviour CORRECT (the tool
        // actually fires) even if the visible text is slightly noisy. A future upgrade
        // would port AICoreProvider's incremental ToolTagParser.
        val fullResponse = fullResponseBuilder.toString()
        val parsedCalls = LiteRtToolPrefix.extractToolCalls(fullResponse)
        if (parsedCalls.isNotEmpty()) {
            val toolParts = parsedCalls.map { call ->
                UIMessagePart.Tool(
                    toolCallId = "litert-tool-${System.nanoTime()}",
                    toolName = call.name,
                    input = call.arguments.toString(),
                    output = emptyList(),
                )
            }
            emit(
                MessageChunk(
                    id = streamId,
                    model = params.model.modelId,
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = toolParts,
                            ),
                            message = null,
                            finishReason = "tool_calls",
                        )
                    ),
                )
            )
        } else {
            // Normal completion — emit the terminal chunk with finishReason = "stop".
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
    }

    /**
     * Render ONE message's content WITHOUT a role marker — the raw turn text.
     *
     * Used both for the warm path (the runtime sends this as-is and the SDK's chat template
     * applies role wrapping, exactly like Gallery) and as the body of each line in
     * [renderHistoryAsChatML]. Tool calls/outputs are kept in the
     * `<tool_call>…</tool_call>` / `<tool_result>…</tool_result>` shape the prompt prefix
     * teaches the model, preserving prompt-engineered tool-calling continuity across turns.
     */
    private fun renderTurnRawText(msg: UIMessage): String = buildString {
        when (msg.role) {
            MessageRole.USER -> {
                append(
                    msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
                )
            }
            MessageRole.ASSISTANT -> {
                // Walk the parts in order so an assistant turn that interleaves text +
                // tool calls is reconstructed faithfully.
                for (part in msg.parts) {
                    when (part) {
                        is UIMessagePart.Text -> append(part.text)
                        is UIMessagePart.Tool -> {
                            val callJson = """{"name":"${part.toolName}","arguments":${part.input.ifBlank { "{}" }}}"""
                            append("<tool_call>").append(callJson).append("</tool_call>")
                            if (part.isExecuted) {
                                val outText = part.output
                                    .filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("") { it.text }
                                if (outText.isNotEmpty()) {
                                    append("\n<tool_result>").append(outText).append("</tool_result>")
                                }
                            }
                        }
                        else -> { /* image/audio/document parts not yet wired into LiteRT input */ }
                    }
                }
            }
            else -> { /* SYSTEM / TOOL are filtered out by the caller */ }
        }
    }

    /** Convert a message into a [Turn] for the runtime's warm/cold KV-cache decision. */
    private fun UIMessage.toTurn(): Turn {
        val turnRole = when (this.role) {
            MessageRole.USER -> ROLE_USER
            MessageRole.ASSISTANT -> ROLE_ASSISTANT
            else -> "other"
        }
        return Turn(turnRole, renderTurnRawText(this))
    }

    /**
     * The cold-path render of the (already SYSTEM/TOOL-filtered) history — what the runtime
     * sends when it cannot reuse a warm KV cache.
     *
     * A single message is sent raw, so the SDK chat template wraps it cleanly (identical to
     * the warm path). Multiple messages are crammed into one marker-formatted ChatML blob:
     * Qwen/Gemma recognise the inner "User:/Assistant:" markers from their instruction-tuning
     * data even when the template only wraps the OUTER content.
     */
    private fun renderColdBlob(messages: List<UIMessage>): String =
        if (messages.size == 1) renderTurnRawText(messages[0])
        else renderHistoryAsChatML(messages)

    private fun renderHistoryAsChatML(messages: List<UIMessage>): String = buildString {
        var first = true
        for (msg in messages) {
            if (msg.role == MessageRole.SYSTEM || msg.role == MessageRole.TOOL) continue
            if (!first) append("\n")
            first = false
            when (msg.role) {
                MessageRole.USER -> append("User: ").append(renderTurnRawText(msg))
                MessageRole.ASSISTANT -> append("Assistant: ").append(renderTurnRawText(msg))
                else -> { /* unreachable — filtered above */ }
            }
        }
        // Trailing assistant cue gives the model a clear "your turn" signal even after the
        // chat template wraps the input. Cheap belt-and-braces vs. the model going silent.
        if (isNotEmpty()) append("\nAssistant: ")
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult = error("LiteRT does not support image generation in 22A")

    /**
     * Translate the LiteRT-LM SDK's raw native error message into a user-actionable
     * exception. The SDK throws bare `RuntimeException` with messages like
     * "Status Code: 3. Message: Input token ids are too long. Exceeding the maximum
     * number of tokens allowed: 4610 >= 4096" — technically correct but unhelpful for
     * users on a chat surface.
     */
    private fun translateSdkError(t: Throwable, effectiveMaxNumTokens: Int): Throwable {
        // Walk the cause chain too — LiteRtRuntime wraps the SDK error inside a
        // RuntimeException of its own ("LiteRT engine could not load this model on
        // this device's GPU OR CPU. … Underlying: …"), so the SDK's own text only
        // appears in the inner cause. Concatenate so the regex catches either layer.
        val joined = generateSequence<Throwable>(t) { it.cause }
            .map { it.message.orEmpty() }
            .joinToString("\n")

        // Context-length overflow. The numbers in the message tell us the actual
        // input vs. the limit; surface both plus what to do next.
        val overflow = Regex("""(\d+)\s*>=\s*(\d+)""").find(joined)
        if (joined.contains("Input token ids are too long", ignoreCase = true) ||
            (joined.contains("Status Code: 3", ignoreCase = true) && overflow != null)) {
            val (have, limit) = overflow?.let { it.groupValues[1] to it.groupValues[2] }
                ?: ("?" to effectiveMaxNumTokens.toString())
            return RuntimeException(
                "This model can only hold $limit tokens of context, but the conversation " +
                    "needs $have. Tap /new to start a fresh chat, switch to a model with a " +
                    "larger context (e.g. Gemma 4 E2B = 32k), OR raise \"Max context\" in " +
                    "Settings → Local · LiteRT if your model file supports it.",
                t,
            )
        }

        // Vision-executor failure. The SDK's stack trace points at
        // `vision_litert_compiled_model_executor.cc` when the model has vision
        // tensors but the runtime couldn't allocate the GPU vision encoder. The
        // pre-flight check covers force-CPU, but this catches the case where the
        // user has GPU enabled and it STILL fails (older device, GPU compute path
        // not supported for the model's vision config). Recovery: pick text-only.
        if (joined.contains("vision_litert_compiled_model_executor", ignoreCase = true) ||
            joined.contains("vision_litert", ignoreCase = true)) {
            return RuntimeException(
                "This model is multimodal (vision) and the GPU vision executor failed " +
                    "to initialise on this device. The most reliable workaround on devices " +
                    "without compatible GPU compute is to pick a text-only model: " +
                    "Gemma3-1B-IT or Qwen2.5-1.5B-Instruct " +
                    "from the catalog in Settings → Local · LiteRT.",
                t,
            )
        }

        // Anything else — keep the original; GenerationHandler's wrapper already
        // strips the stack trace from the LLM-facing envelope.
        return t
    }

    companion object {
        /** Hard char-cap for the joined SYSTEM-message text. Tight on purpose: we
         *  want the user's actual system prompt ("You are X") to land, but NOT
         *  agent-core's auto-loaded skill body (~3-5k tokens of persona + tool
         *  docs that small models can't usefully consume). 500 chars ≈ 125 tokens. */
        private const val SYSTEM_MESSAGE_CHAR_BUDGET = 500

        /** Hard char-cap for the rendered ChatML history. ~3000 chars ≈ 750 tokens. */
        private const val HISTORY_CHAR_BUDGET = 3000

        /** Max tools surfaced in the compact tool prefix. Beyond ~30 tools the
         *  prefix alone consumes more context than a 4k model can spare for
         *  system + history + output. The compact prefix is ordered by the caller;
         *  later tools are dropped first (with a "and N more" note) so the user
         *  can still call the most important ones. */
        private const val MAX_TOOLS_IN_PREFIX = 25

        /** Hard char-cap for the compact tool prefix. ~2000 chars ≈ 500 tokens. */
        private const val TOOL_PREFIX_CHAR_BUDGET = 2000
    }
}
