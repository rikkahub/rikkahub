package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
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
            val status: Int = try {
                generativeModel.checkStatus()
            } catch (t: Throwable) {
                Log.w(TAG, "checkStatus threw", t)
                error(translateAICoreError(t))
            }
            if (status != FeatureStatus.AVAILABLE) {
                error(unavailableMessage(status))
            }
            try {
                generativeModel.warmup()
            } catch (t: Throwable) {
                Log.w(TAG, "warmup threw", t)
                error(translateAICoreError(t))
            }

            val systemPrefix = buildString {
                val baseSystem = buildSystemPrefix(messages)
                if (baseSystem.isNotBlank()) appendLine(baseSystem).appendLine()
                if (params.tools.isNotEmpty()) appendLine(buildToolProtocolPrompt(params.tools))
            }.trim()
            val prompt = formatPromptFromMessages(messages)
            val temperature = (params.temperature ?: 0.7f).coerceIn(0f, 1f)
            val request = generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                if (systemPrefix.isNotBlank()) {
                    this.promptPrefix = PromptPrefix(systemPrefix)
                }
                params.topP?.let { /* topP not exposed in ML Kit GenAI prompt API */ }
            }

            val streamId = "aicore-${System.currentTimeMillis()}"
            val parser = ToolTagParser()
            try {
                generativeModel.generateContentStream(request).collect { response ->
                    val candidate = response.candidates.firstOrNull()
                    val rawDelta = candidate?.text.orEmpty()
                    val rawFinish = candidate?.finishReason?.toString()
                    val parts = parser.feed(rawDelta)
                    if (parts.isNotEmpty()) {
                        emit(
                            MessageChunk(
                                id = streamId,
                                model = params.model.modelId,
                                choices = listOf(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = UIMessage(
                                            role = MessageRole.ASSISTANT,
                                            parts = parts,
                                        ),
                                        message = null,
                                        // If a tool tag was closed in this delta, signal
                                        // tool_calls so the GenerationHandler dispatches the
                                        // tool and resumes the conversation. Otherwise pass
                                        // through whatever the model declared (usually null).
                                        finishReason = parser.consumePendingFinishReason()
                                            ?: rawFinish,
                                    )
                                ),
                            )
                        )
                    } else if (rawFinish != null) {
                        // No content in this chunk but stream closed. Flush any partial buffer
                        // as text so it isn't dropped.
                        val flushed = parser.flushPending()
                        emit(
                            MessageChunk(
                                id = streamId,
                                model = params.model.modelId,
                                choices = listOf(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = UIMessage(
                                            role = MessageRole.ASSISTANT,
                                            parts = flushed,
                                        ),
                                        message = null,
                                        finishReason = parser.consumePendingFinishReason()
                                            ?: rawFinish,
                                    )
                                ),
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "generateContentStream threw", t)
                error(translateAICoreError(t))
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
     * Maps AICore's raw error messages to a user-actionable hint. The most common one we hit
     * on launch is `ErrorCode 606 - FEATURE_NOT_FOUND` which means the device has the AICore
     * system app installed but is not enrolled in the GenAI Prompt-API early-access channel.
     */
    private fun translateAICoreError(t: Throwable): String {
        val msg = (t.message ?: t::class.java.simpleName)
        return when {
            msg.contains("606") || msg.contains("FEATURE_NOT_FOUND", ignoreCase = true) ->
                "AICore prompt-API not enrolled on this device. Install the AICore app from the Play Store, then enrol in the GenAI Prompt-API early-access program at https://goo.gle/aicore-prompt-eap and reboot. Raw: $msg"
            msg.contains("PREPARATION_ERROR", ignoreCase = true) ->
                "AICore is still preparing the model. Wait 30s and retry, or open Settings → Apps → AICore → Storage and clear cache. Raw: $msg"
            else -> "AICore error: $msg"
        }
    }

    /**
     * Concatenates all SYSTEM-role messages into a single system instruction string the ML
     * Kit prompt API can attach via [PromptPrefix]. Without this the AICore model would have
     * no idea which app it is running in or what its persona is — replies would be pure
     * generic Q&A.
     */
    private fun buildSystemPrefix(messages: List<UIMessage>): String = messages
        .filter { it.role == MessageRole.SYSTEM }
        .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
        .joinToString("\n\n") { it.text.trim() }
        .trim()

    /**
     * Flattens the conversation into a single text prompt the ML Kit GenAI surface expects.
     * SYSTEM messages are NOT included here — they go in the [PromptPrefix] instead. Tool
     * calls and image/audio parts are collapsed to text since the prompt-API at this version
     * is text-only.
     */
    private fun formatPromptFromMessages(messages: List<UIMessage>): String = buildString {
        for (message in messages) {
            if (message.role == MessageRole.SYSTEM) continue
            val role = when (message.role) {
                MessageRole.SYSTEM -> continue
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "model"
                MessageRole.TOOL -> "tool"
            }
            // Concatenate text + tool-call envelopes + tool outputs so the model sees the
            // full ReAct-style trace of "I called tap, here's the result, now I plan...".
            val textBuilder = StringBuilder()
            for (part in message.parts) {
                when (part) {
                    is UIMessagePart.Text -> textBuilder.append(part.text)
                    is UIMessagePart.Tool -> {
                        textBuilder.append("\n<tool_call>{\"name\":\"")
                            .append(part.toolName).append("\",\"input\":")
                            .append(part.input.ifBlank { "{}" })
                            .append("}</tool_call>")
                        val out = part.output.filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }
                        if (out.isNotBlank()) {
                            textBuilder.append("\n<tool_result>")
                                .append(out)
                                .append("</tool_result>")
                        }
                    }
                    else -> { /* ignore image / reasoning / etc. for the on-device prompt */ }
                }
            }
            val text = textBuilder.toString().trim()
            if (text.isNotBlank()) {
                append(role).append(": ").append(text).append('\n')
            }
        }
        append("model: ")
    }
}

/**
 * Builds the system-prefix block that teaches the model the tool-call protocol. ML Kit's
 * prompt API has no native function-calling at v1.0.0-beta2, so we rely on the model
 * emitting `<tool_call>{...}</tool_call>` markup, which [ToolTagParser] then parses out
 * of the stream and converts into structured [UIMessagePart.Tool] parts.
 *
 * Gemini Nano follows the protocol reliably for the vast majority of turns; if it emits
 * malformed JSON the parser falls back to streaming the text as plain output.
 */
private fun buildToolProtocolPrompt(tools: List<Tool>): String = buildString {
    appendLine("You are running inside RikkaHub, an Android on-device agent.")
    appendLine()
    appendLine("You have access to the following tools. To call one, output exactly:")
    appendLine("<tool_call>{\"name\": \"<tool_name>\", \"input\": <json args>}</tool_call>")
    appendLine("After the call, the system will reply with a <tool_result> block. Read the")
    appendLine("result, decide your next step, and either call another tool or reply with a")
    appendLine("plain-text answer to the user. Do NOT explain that you are calling a tool —")
    appendLine("just emit the tool_call block and wait. Use one tool per turn unless the")
    appendLine("task obviously needs multiple sequential calls.")
    appendLine()
    appendLine("Available tools:")
    tools.forEach { tool ->
        append("- ").append(tool.name).append(": ").append(tool.description.trim()).appendLine()
        val schema = runCatching { tool.parameters() }.getOrNull()
        if (schema is InputSchema.Obj) {
            val schemaJson = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    schema.properties.forEach { (k, v) -> put(k, v) }
                }
                schema.required?.let { req ->
                    put("required", kotlinx.serialization.json.JsonArray(
                        req.map { kotlinx.serialization.json.JsonPrimitive(it) }
                    ))
                }
            }
            append("  schema: ").appendLine(schemaJson.toString())
        }
    }
}.trim()

/**
 * Streaming parser that walks the AICore output token-by-token, splitting it into plain
 * text segments and complete `<tool_call>{...}</tool_call>` blocks. Tool calls are emitted
 * as [UIMessagePart.Tool] with the raw JSON args; the GenerationHandler then dispatches
 * the matching tool and feeds the result back on the next turn.
 *
 * Maintains an internal buffer because tags split across stream chunks. When we detect the
 * opening `<tool_call>` we hold subsequent characters until we see the closing tag, then
 * parse the JSON body. Plain text outside any tag is flushed as it arrives so the user
 * sees streaming output for normal Q&A turns.
 */
private class ToolTagParser {
    private val buffer = StringBuilder()
    private var inToolCall = false
    private var pendingFinishReason: String? = null

    private val openTag = "<tool_call>"
    private val closeTag = "</tool_call>"

    fun feed(delta: String): List<UIMessagePart> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val out = mutableListOf<UIMessagePart>()
        while (true) {
            if (!inToolCall) {
                val openIdx = buffer.indexOf(openTag)
                if (openIdx < 0) {
                    // No open tag yet — flush everything we have UNLESS the tail might be
                    // the start of an open tag, in which case keep it buffered.
                    val safe = buffer.length - (openTag.length - 1).coerceAtLeast(0)
                    if (safe > 0) {
                        val text = buffer.substring(0, safe)
                        buffer.delete(0, safe)
                        if (text.isNotEmpty()) out += UIMessagePart.Text(text)
                    }
                    break
                }
                if (openIdx > 0) {
                    val pre = buffer.substring(0, openIdx)
                    if (pre.isNotEmpty()) out += UIMessagePart.Text(pre)
                }
                buffer.delete(0, openIdx + openTag.length)
                inToolCall = true
            }
            // We're inside a tool_call — wait for close tag.
            val closeIdx = buffer.indexOf(closeTag)
            if (closeIdx < 0) break
            val body = buffer.substring(0, closeIdx).trim()
            buffer.delete(0, closeIdx + closeTag.length)
            inToolCall = false
            val parsed = parseToolCallBody(body)
            if (parsed != null) {
                out += parsed
                pendingFinishReason = "tool_calls"
            } else {
                // Malformed — surface as plain text so the user sees the model's intent.
                out += UIMessagePart.Text("<tool_call>$body</tool_call>")
            }
        }
        return out
    }

    fun flushPending(): List<UIMessagePart> {
        if (buffer.isEmpty()) return emptyList()
        val txt = buffer.toString()
        buffer.clear()
        return listOf(UIMessagePart.Text(txt))
    }

    fun consumePendingFinishReason(): String? {
        val r = pendingFinishReason
        pendingFinishReason = null
        return r
    }

    private fun parseToolCallBody(body: String): UIMessagePart.Tool? = try {
        val obj: JsonObject = Json.parseToJsonElement(body) as JsonObject
        val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val input = obj["input"]?.toString() ?: "{}"
        UIMessagePart.Tool(
            toolCallId = "aicore-tool-${System.nanoTime()}",
            toolName = name,
            input = input,
            output = emptyList(),
        )
    } catch (_: Throwable) {
        null
    }
}
