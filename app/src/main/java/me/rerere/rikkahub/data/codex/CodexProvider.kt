package me.rerere.rikkahub.data.codex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.rikkahub.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class CodexProvider(
    private val context: Context,
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
    private val json: Json,
) : Provider<ProviderSetting.Codex> {
    private val responseApi = ResponseAPI(client)
    private val eventSourceClient by lazy {
        client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && response.header("Content-Type") == null) {
                    val body = response.body
                    response.newBuilder()
                        .header("Content-Type", "text/event-stream")
                        .body(
                            body.source().asResponseBody(
                                contentType = "text/event-stream".toMediaType(),
                                contentLength = body.contentLength(),
                            )
                        )
                        .build()
                } else {
                    response
                }
            }
            .build()
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Codex): List<Model> =
        withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            val request = Request.Builder()
                .url("$CODEX_API_BASE/models?client_version=$CLIENT_VERSION")
                .codexHeaders(account)
                .get()
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to get Codex models: ${response.code} ${response.body.string()}")
            }
            val models = json.parseToJsonElement(response.body.string())
                .jsonObject["models"]?.jsonArray
                ?: return@withContext emptyList()
            models.mapNotNull { element ->
                val item = element.jsonObject
                if (item["visibility"]?.jsonPrimitive?.contentOrNull != "list") {
                    return@mapNotNull null
                }
                val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val modalities = item["input_modalities"]?.jsonArray
                    ?.mapNotNull { modality ->
                        when (modality.jsonPrimitive.contentOrNull) {
                            "text" -> Modality.TEXT
                            "image" -> Modality.IMAGE
                            else -> null
                        }
                    }
                    ?.ifEmpty { listOf(Modality.TEXT) }
                    ?: listOf(Modality.TEXT, Modality.IMAGE)
                Model(
                    modelId = slug,
                    displayName = item["display_name"]?.jsonPrimitive?.contentOrNull ?: slug,
                    inputModalities = modalities,
                    abilities = buildList {
                        add(ModelAbility.TOOL)
                        if (
                            item["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true ||
                            item["supports_reasoning_summaries"]?.jsonPrimitive?.booleanOrNull == true
                        ) {
                            add(ModelAbility.REASONING)
                        }
                    },
                )
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var collected = listOf(UIMessage.assistant(""))
        var usage: me.rerere.ai.core.TokenUsage? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            collected = collected.handleMessageChunk(chunk, params.model)
            usage = chunk.usage ?: usage
        }
        return MessageChunk(
            id = "",
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = collected.last(),
                    finishReason = "stop",
                )
            ),
            usage = usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val reasoningEffort = params.model.abilities
            .takeIf { it.contains(ModelAbility.REASONING) }
            ?.let { codexReasoningEffort(params.reasoningLevel) }
        val account = repository.acquireAccount()
        val syntheticSetting = ProviderSetting.OpenAI(
            id = providerSetting.id,
            enabled = providerSetting.enabled,
            name = providerSetting.name,
            models = providerSetting.models,
            baseUrl = "https://api.openai.com/v1",
            useResponseApi = true,
        )
        val baseRequestBody = responseApi.buildRequestBody(
            providerSetting = syntheticSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val requestBody = buildJsonObject {
            baseRequestBody.forEach { (key, value) -> put(key, value) }
            if (baseRequestBody["instructions"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                put("instructions", DEFAULT_INSTRUCTIONS)
            }
            reasoningEffort?.let { effort ->
                put("reasoning", buildJsonObject {
                    put("effort", effort)
                    put("summary", "auto")
                })
            }
        }
        val request = Request.Builder()
            .url("$CODEX_API_BASE/responses")
            .headers(params.customHeaders.toHeaders())
            .codexHeaders(account)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                parseCodexUsage(response.headers)?.let { usage ->
                    launch { repository.updateUsage(account.id, usage) }
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                val payload = runCatching {
                    json.parseToJsonElement(data).jsonObject
                }.getOrElse {
                    close(it)
                    return
                }
                val eventType = payload["type"]?.jsonPrimitive?.contentOrNull ?: type
                if (eventType in FINAL_RESPONSE_EVENTS) {
                    parseTokenUsage(payload)?.let { usage ->
                        trySend(
                            MessageChunk(
                                id = payload["response"]?.jsonObject
                                    ?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
                                model = params.model.modelId,
                                choices = emptyList(),
                                usage = usage,
                            )
                        )
                    }
                    if (eventType == "response.failed") {
                        close(
                            IllegalStateException(
                                parseErrorMessage(payload)
                                    ?: context.getString(R.string.codex_response_failed)
                            )
                        )
                    } else if (eventType == "response.incomplete") {
                        close(IllegalStateException(parseCodexIncompleteMessage(payload)))
                    } else {
                        close()
                    }
                    return
                }
                runCatching { responseApi.parseResponseDelta(payload) }
                    .onSuccess { chunk ->
                        if (chunk != null) trySend(chunk)
                    }
                    .onFailure { close(it) }
                if (eventType == "error") {
                    close(
                        IllegalStateException(
                            parseErrorMessage(payload)
                                ?: context.getString(R.string.codex_response_failed)
                        )
                    )
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.let {
                    parseCodexUsage(it.headers)?.let { usage ->
                        launch { repository.updateUsage(account.id, usage) }
                    }
                    if (it.code == 401) {
                        launch { repository.markInvalid(account.id) }
                    }
                }
                val detail = response
                    ?.takeUnless { it.isSuccessful }
                    ?.body
                    ?.string()
                close(t ?: IllegalStateException("Codex request failed: ${response?.code} $detail"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }
        val eventSource = EventSources.createFactory(eventSourceClient).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Codex provider")
    }

    private fun Request.Builder.codexHeaders(account: CodexAccount): Request.Builder {
        return header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-Id", account.chatgptAccountId)
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "codex_cli_rs")
    }

    private fun parseTokenUsage(payload: JsonObject): TokenUsage? {
        val usage = payload["usage"]?.jsonObject
            ?: payload["response"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = input,
            completionTokens = output,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: input + output,
            cachedTokens = usage["input_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["cached_input_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0,
        )
    }

    private fun parseErrorMessage(payload: JsonObject): String? {
        return runCatching {
            payload["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: payload["response"]?.jsonObject
                    ?.get("error")?.jsonObject
                    ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private companion object {
        const val CODEX_API_BASE = "${CodexAccountRepository.CODEX_BASE_URL}/codex"
        const val CLIENT_VERSION = "0.139.0"
        const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."
        val FINAL_RESPONSE_EVENTS = setOf(
            "response.completed",
            "response.done",
            "response.incomplete",
            "response.failed",
        )
    }
}

internal fun codexReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> "xhigh"
        ReasoningLevel.OFF -> "none"
    }
}

internal fun parseCodexIncompleteMessage(payload: JsonObject): String {
    val reason = runCatching {
        payload["response"]?.jsonObject
            ?.get("incomplete_details")?.jsonObject
            ?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: payload["incomplete_details"]?.jsonObject
                ?.get("reason")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return if (reason.isNullOrBlank()) {
        "Codex response incomplete"
    } else {
        "Codex response incomplete: $reason"
    }
}
