package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.util.AiLog
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.STREAM_MAX_RETRIES
import me.rerere.ai.util.StreamRetryController
import me.rerere.ai.util.bufferStreamChunks
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.isRetryableStreamFailure
import me.rerere.ai.util.jitteredBackoffMillis
import me.rerere.ai.util.json
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.retryAfterMillisFromHeaders
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ChatGPTProvider"

// codex_cli_rs is the originator the ChatGPT (Codex) backend gates on; a plain OpenAI-compatible
// client (without it) is rejected. Pinned to the open-source openai/codex wire contract.
private const val CHATGPT_ORIGINATOR = "codex_cli_rs"
private const val CHATGPT_CLIENT_VERSION = "0.20.0"

private const val EXPIRED_TOKEN_MESSAGE =
    "ChatGPT access token expired — paste a new one in provider settings."

// The Codex base instructions, sent as the Responses `instructions` field. The backend expects the
// official Codex system prompt; this is the gate fingerprint (mirrors how ClaudeProvider pins
// CLAUDE_FP_*). Sourced from openai/codex codex-rs/protocol/src/prompts/base_instructions/default.md.
private const val CODEX_INSTRUCTIONS =
    "You are a coding agent running in the Codex CLI, a terminal-based coding assistant. Codex CLI is an open source project led by OpenAI. You are expected to be precise, safe, and helpful."

/**
 * ChatGPT subscription provider over the Codex backend (`https://chatgpt.com/backend-api/codex`).
 *
 * Paste-only access token (a JWT) — no OAuth/device-code login or refresh (issue #285). Uses the
 * OpenAI Responses-API wire shape (SSE), with `stream:true` and `store:false` forced (both required
 * by the backend) and the Codex `instructions` prepended. The token is used verbatim (NOT via
 * KeyRoulette, which splits on whitespace/commas and would corrupt a JWT), exactly like
 * ClaudeProvider's OAuth path. Request body assembly and SSE delta parsing are reused from
 * [ResponseAPI] (same wire format) to avoid duplicating ~400 lines of Responses logic.
 */
class ChatGPTProvider(
    private val client: OkHttpClient,
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
    // SSE 使用带有短 readTimeout 的专用客户端，使冻结的后台 socket 快速失败 -> onFailure -> close。
    private val streamClient: OkHttpClient = client,
) : Provider<ProviderSetting.ChatGPT> {

    private val responseAPI = ResponseAPI(client, KeyRoulette.default(), streamClient)

    // Used verbatim — see KeyRoulette warning above. A fresh session_id per request matches codex-cli.
    private fun Request.Builder.applyChatGptHeaders(p: ProviderSetting.ChatGPT): Request.Builder = this
        .addHeader("Authorization", "Bearer ${p.accessToken}")
        .addHeader("originator", CHATGPT_ORIGINATOR)
        .addHeader("session_id", Uuid.random().toString())

    // Fail closed on a token we can PROVE is expired: throw the typed expiry message before any
    // network call, so the user sees a clear "paste a new one" instead of a raw 401. A non-JWT /
    // unparseable token falls through to the server's own auth error (isChatGptTokenExpired == false).
    private fun requireNonExpired(p: ProviderSetting.ChatGPT) {
        if (isChatGptTokenExpired(p.accessToken, Clock.System.now().epochSeconds)) {
            throw HttpException(EXPIRED_TOKEN_MESSAGE)
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.ChatGPT): List<Model> =
        withContext(Dispatchers.IO) {
            requireNonExpired(providerSetting)
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models?client_version=$CHATGPT_CLIENT_VERSION")
                .applyChatGptHeaders(providerSetting)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 401) error(EXPIRED_TOKEN_MESSAGE)
                error("Failed to get models: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Model(modelId = id, displayName = id)
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("ChatGPT provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        // The backend forces SSE even for a non-streaming request, so there is no JSON-body path.
        // Reuse the proven streamText SSE pipeline (auth, retry, teardown) and fold its deltas into
        // the single `message` choice non-streaming callers expect — no hand-rolled buffer parsing.
        val acc = streamText(providerSetting, messages, params).fold(
            StreamAccumulator(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()), null)
        ) { state, chunk ->
            StreamAccumulator(state.message + chunk, chunk.usage ?: state.usage)
        }

        MessageChunk(
            id = "",
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(index = 0, delta = null, message = acc.message, finishReason = null)
            ),
            usage = acc.usage,
        )
    }

    private data class StreamAccumulator(
        val message: UIMessage,
        val usage: me.rerere.ai.core.TokenUsage?,
    )

    override suspend fun streamText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        requireNonExpired(providerSetting)
        val request = buildRequest(providerSetting, messages, params)
        AiLog.request(TAG, "chatgpt", params.model.modelId, true)

        val factory = EventSources.createFactory(streamClient)
        lateinit var listener: EventSourceListener

        // Pre-first-frame retry state lives behind the controller's lock — the proven structure
        // shared with ResponseAPI/ClaudeProvider; do not invent a new teardown path.
        val controller = StreamRetryController(STREAM_MAX_RETRIES) {
            StreamRetryController.Cancellable(factory.newEventSource(request, listener)::cancel)
        }

        fun scheduleRetry(attempt: Int, backoffMillis: Long) {
            Log.i(TAG, "transient pre-first-frame failure, retrying (attempt $attempt/$STREAM_MAX_RETRIES)")
            launch {
                delay(backoffMillis)
                controller.reopen()
            }
        }

        listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                controller.onFrame()
                if (data == "[DONE]") {
                    close()
                    return
                }
                AiLog.event(TAG, type, id)
                val frame = json.parseToJsonElement(data).jsonObject
                val chunk = try {
                    responseAPI.parseResponseDelta(frame)
                } catch (e: Exception) {
                    // A recognized-but-malformed frame is a real failure; surface it rather than
                    // log-and-swallow (which would hand the user a quietly-incomplete response).
                    AiLog.parseFailure(TAG, e)
                    close(e)
                    return
                }
                if (chunk != null) {
                    trySend(chunk)
                }
                if (type == "response.completed") {
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val httpCode = response?.code
                val retryAfterMs = retryAfterMillisFromHeaders(
                    response?.header("retry-after-ms"),
                    response?.header("Retry-After")
                )
                val outcome = controller.onFailure(
                    transient = isRetryableStreamFailure(t, httpCode),
                    backoffFor = { attempt -> jitteredBackoffMillis(attempt - 1, retryAfterMs) },
                    error = t,
                )
                if (outcome is StreamRetryController.Outcome.Retry) {
                    scheduleRetry(outcome.attempt, outcome.backoffMillis)
                    return
                }

                AiLog.failure(TAG, t, response?.code)

                // A 401 here is the expired/invalid-token case requireNonExpired could not prove
                // (opaque token, clock skew): surface the clear expiry message as a backstop.
                var exception: Throwable? = if (httpCode == 401) HttpException(EXPIRED_TOKEN_MESSAGE) else t

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (httpCode != 401 && !bodyRaw.isNullOrBlank()) {
                        exception = Json.parseToJsonElement(bodyRaw).parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    AiLog.parseFailure(TAG, e)
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                val outcome = controller.onClosed(
                    backoffFor = { attempt -> jitteredBackoffMillis(attempt - 1, null) },
                )
                if (outcome is StreamRetryController.Outcome.Retry) {
                    scheduleRetry(outcome.attempt, outcome.backoffMillis)
                } else {
                    close()
                }
            }
        }

        controller.start()

        awaitClose {
            Log.d(TAG, "closing eventSource")
            controller.close()
        }
    }.bufferStreamChunks()

    private fun buildRequest(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Request {
        val requestBody = buildRequestBody(providerSetting, messages, params)
        return Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .applyChatGptHeaders(providerSetting)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()
    }

    // Reuse ResponseAPI's Responses body builder (same wire format), then FORCE stream=true (backend
    // requires it) and PREPEND the Codex instructions gate fingerprint. The Responses builder reads
    // only baseUrl/host from its OpenAI setting (never the apiKey on this path — we attach auth on the
    // Request, not the body), so a throwaway OpenAI setting carrying the ChatGPT baseUrl is safe.
    private fun buildRequestBody(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): JsonObject {
        val proxySetting = ProviderSetting.OpenAI(baseUrl = providerSetting.baseUrl)
        val base = responseAPI.buildRequestBody(
            providerSetting = proxySetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val callerInstructions = (base["instructions"] as? JsonPrimitive)?.contentOrNull
        val mergedInstructions = if (callerInstructions.isNullOrBlank()) {
            CODEX_INSTRUCTIONS
        } else {
            "$CODEX_INSTRUCTIONS\n\n$callerInstructions"
        }
        // store:false is already set by buildRequestBody; re-assert stream:true and the instructions.
        return JsonObject(
            base + buildJsonObject {
                put("stream", true)
                put("store", false)
                put("instructions", mergedInstructions)
            }
        )
    }
}
