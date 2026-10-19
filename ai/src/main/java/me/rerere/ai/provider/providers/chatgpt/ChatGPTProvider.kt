package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.UsageWindow
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponsesEndpoint
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Instant

// 参照 openai/codex（codex-rs/model-provider-info、codex-rs/login）
internal const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
internal const val CODEX_ORIGINATOR = "codex_cli_rs"

// 订阅用量，参照 codex-rs/backend-client 的 rate_limit_status_url
private const val CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

// 模型列表按客户端版本过滤，太旧会拿不到新模型
private const val CODEX_CLIENT_VERSION = "0.163.0"

// Codex 后端不接受这些参数（Codex CLI 也从不发送）
private val UNSUPPORTED_BODY_FIELDS = setOf("max_output_tokens", "temperature", "top_p")

/**
 * ChatGPT 账号登录后走 Codex 后端。协议是 Responses API，但和 api.openai.com 有几处不同：
 *
 * - 只支持流式，`store` 必须为 false；[generateText] 用流式结果拼出完整消息
 * - system 提示以 developer 消息放在 `input` 开头，不使用 `instructions` 字段
 * - 鉴权除了 Bearer token，还要带 `ChatGPT-Account-ID` 和 `originator`
 */
class ChatGPTProvider(
    client: OkHttpClient,
    private val credentials: AccountCredentialProvider,
) : Provider<ProviderSetting.ChatGPT> {
    private val client = client.withAccountAuthenticator(credentials)
    private val responseAPI = ResponseAPI(this.client)

    override suspend fun listModels(providerSetting: ProviderSetting.ChatGPT): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$CODEX_BASE_URL/models?client_version=$CODEX_CLIENT_VERSION")
                .apply { configureAuth(providerSetting) }
                .get()
                .build()
            client.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error("Failed to get models: ${response.code} $body")
                }
                parseCodexModels(body)
            }
        }

    override suspend fun getQuota(providerSetting: ProviderSetting.ChatGPT): ProviderQuota =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(CODEX_USAGE_URL)
                .apply { configureAuth(providerSetting) }
                .get()
                .build()
            client.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error("Failed to get usage: ${response.code} $body")
                }
                parseCodexUsage(body)
            }
        }

    override suspend fun streamText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = responseAPI.streamText(
        endpoint = ResponsesEndpoint(
            url = "$CODEX_BASE_URL/responses",
            configureRequest = {
                configureAuth(providerSetting, params)
                params.sessionId?.let { header("session-id", it) }
            },
            transformBody = { body -> codexRequestBody(body, promptCacheKey = params.sessionId) },
        ),
        messages = messages,
        params = params,
    )

    override suspend fun generateText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult {
        val handler = StreamChunkHandler(params.model)
        var result = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id))
        var finish: StreamChunk.Finish? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            if (chunk is StreamChunk.Finish) finish = chunk
            result = handler.handle(result, chunk)
        }
        val message = result.last()
        return TextGenerationResult(
            id = finish?.responseId.orEmpty(),
            model = finish?.model ?: params.model.modelId,
            message = message,
            finishReason = finish?.finishReason,
            usage = message.usage,
        )
    }

    private suspend fun Request.Builder.configureAuth(
        setting: ProviderSetting.ChatGPT,
        params: TextGenerationParams? = null,
    ) = configureAccountAuth(credentials, setting, params?.customHeaders.orEmpty()) { credential ->
        header("Authorization", "Bearer ${credential.accessToken}")
        credential.accountId?.let { header("ChatGPT-Account-ID", it) }
        header("originator", CODEX_ORIGINATOR)
    }
}

/** 把 [ResponseAPI] 生成的请求体改成 Codex 后端接受的形式，保持字段顺序 */
internal fun codexRequestBody(body: JsonObject, promptCacheKey: String?): JsonObject {
    val instructions = body["instructions"]?.jsonPrimitiveOrNull?.contentOrNull
    return buildJsonObject {
        body.forEach { (key, value) ->
            when (key) {
                "instructions" -> {}
                in UNSUPPORTED_BODY_FIELDS -> {}
                "stream" -> put(key, true)
                "store" -> put(key, false)
                "input" -> putJsonArray(key) {
                    if (!instructions.isNullOrEmpty()) {
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", "developer")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", instructions)
                                })
                            }
                        })
                    }
                    value.jsonArray.forEach { add(it) }
                }

                else -> put(key, value)
            }
        }
        // store=false 时多轮推理要靠加密的推理内容接上
        if ("include" !in body) {
            put("include", buildJsonArray { add("reasoning.encrypted_content") })
        }
        if (promptCacheKey != null && "prompt_cache_key" !in body) {
            put("prompt_cache_key", promptCacheKey)
        }
    }
}

/** 解析 `GET /models` 的结果，只保留 Codex 选择器里可见的模型 */
internal fun parseCodexModels(body: String): List<Model> {
    val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty()
        .map { it.jsonObject }
    val visible = models.filter { it.string("visibility") == "list" }.ifEmpty { models }
    return visible.mapNotNull { model ->
        val slug = model.string("slug") ?: return@mapNotNull null
        val inputModalities = model["input_modalities"]?.jsonArray
            ?.mapNotNull { modality ->
                when (modality.jsonPrimitiveOrNull?.contentOrNull) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }
            ?.ifEmpty { null }
            ?: listOf(Modality.TEXT, Modality.IMAGE)
        val reasoning = model["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true
        Model(
            modelId = slug,
            displayName = model.string("display_name") ?: slug,
            inputModalities = inputModalities,
            abilities = if (reasoning) listOf(ModelAbility.TOOL, ModelAbility.REASONING) else listOf(ModelAbility.TOOL),
            contextLength = model["context_window"]?.jsonPrimitiveOrNull?.intOrNull ?: Model().contextLength,
        )
    }
}

/** 解析 `GET /wham/usage`，取 `rate_limit` 下的两个窗口（通常是 5 小时和一周） */
internal fun parseCodexUsage(body: String): ProviderQuota.UsageLimits {
    val rateLimit = json.parseToJsonElement(body).jsonObject["rate_limit"] as? JsonObject
    val windows = listOf("primary_window", "secondary_window").mapNotNull { key ->
        val window = rateLimit?.get(key) as? JsonObject ?: return@mapNotNull null
        val usedPercent = window["used_percent"]?.jsonPrimitiveOrNull?.doubleOrNull ?: return@mapNotNull null
        val seconds = window["limit_window_seconds"]?.jsonPrimitiveOrNull?.longOrNull
        val resetAt = window["reset_at"]?.jsonPrimitiveOrNull?.longOrNull
        UsageWindow(
            usedPercent = usedPercent,
            windowMinutes = seconds?.takeIf { it > 0 }?.let { ((it + 59) / 60).toInt() },
            resetsAt = resetAt?.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) },
        )
    }
    return ProviderQuota.UsageLimits(
        windows = windows,
        limitReached = rateLimit?.get("limit_reached")?.jsonPrimitiveOrNull?.booleanOrNull == true,
    )
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
