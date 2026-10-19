package me.rerere.ai.provider.providers.grok

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.CustomHeader
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
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.providers.openai.ResponsesEndpoint
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.fetchBody
import me.rerere.ai.util.json
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.stringOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Clock
import kotlin.time.Instant

// 参照 can1357/oh-my-pi（registry/oauth/xai-oauth.ts、usage/xai-oauth.ts、catalog 的 xai-oauth.kdl）
private const val XAI_BASE_URL = "https://api.x.ai/v1"

// Grok CLI 的代理，模型目录和订阅计费在这里
private const val GROK_CLI_BASE_URL = "https://cli-chat-proxy.grok.com/v1"

// 图片、语音等不是对话模型
private val NON_CHAT_MODEL = Regex("imagine|tts|embed|video", RegexOption.IGNORE_CASE)

private const val WEEK_MINUTES = 7 * 24 * 60

/**
 * xAI 对请求体的要求：system 提示以 system 消息放在 `input` 开头；不接受 `reasoning.summary`；
 * `reasoning.effort` 的取值见 [grokEffort]。
 * 不保存会话，所有模型都请求加密推理内容来接上多轮推理。
 */
internal val GROK_CAPABILITIES = ResponseProviderCapabilities(
    supportsReasoningSummary = false,
    systemMessageRole = "system",
    alwaysIncludeEncryptedContent = true,
    reasoningEffort = ::grokEffort,
)

// grok-4.20 的推理和非推理模型带任何 reasoning.effort（包括 none）都会报参数错误
private val MODELS_WITHOUT_REASONING_EFFORT = Regex("""^grok-4\.20(-\d{4})?-(non-)?reasoning$""")

/**
 * 与 Vercel AI SDK 的 xAI provider 一致：关闭发 `none`（grok-4.3 及更新的推理模型支持），
 * `xhigh` 只有 grok-4.6 支持，其他模型落到 `high`。
 */
private fun grokEffort(level: ReasoningLevel, modelId: String): String? {
    if (MODELS_WITHOUT_REASONING_EFFORT.matches(modelId)) return null
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> if (modelId == "grok-4.6") "xhigh" else "high"
    }
}

/**
 * SuperGrok 订阅（xAI 账号登录）。对话走 api.x.ai 的 Responses API，用 OAuth access token 作 Bearer：
 *
 * - 请求体的差异见 [GROK_CAPABILITIES]
 * - 模型目录合并 Grok CLI 的 models-v2 和 api.x.ai 的 /models
 * - 额度查 Grok CLI 的 billing：旧的按周额度，或统一计费的按月额度
 */
class GrokProvider(
    client: OkHttpClient,
    private val credentials: AccountCredentialProvider,
) : Provider<ProviderSetting.Grok> {
    private val client = client.withAccountAuthenticator(credentials)
    private val responseAPI = ResponseAPI(this.client)

    override suspend fun listModels(providerSetting: ProviderSetting.Grok): List<Model> = coroutineScope {
        // 两个目录拿到一个就够用
        val cliRequest = async {
            runCatching { getJson(providerSetting, "$GROK_CLI_BASE_URL/models-v2", grokCli = true) }
        }
        val apiRequest = async { runCatching { getJson(providerSetting, "$XAI_BASE_URL/models") } }
        val cli = cliRequest.await()
        val api = apiRequest.await()
        if (cli.isFailure && api.isFailure) throw cli.exceptionOrNull()!!
        parseGrokModels(cliCatalog = cli.getOrNull(), apiCatalog = api.getOrNull())
    }

    override suspend fun getQuota(providerSetting: ProviderSetting.Grok): ProviderQuota {
        val credits = getJson(providerSetting, "$GROK_CLI_BASE_URL/billing?format=credits", grokCli = true)
        val weekly = parseGrokWeeklyUsage(credits)
        if (weekly != null && !credits.isUnifiedBilling()) return weekly
        // 统一计费账号的按月额度在不带 format 的接口上
        val monthly = parseGrokMonthlyUsage(getJson(providerSetting, "$GROK_CLI_BASE_URL/billing", grokCli = true))
        return monthly ?: weekly ?: ProviderQuota.UsageLimits(windows = emptyList(), limitReached = false)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = responseAPI.streamText(endpoint(providerSetting, params), messages, params)

    override suspend fun generateText(
        providerSetting: ProviderSetting.Grok,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = responseAPI.generateText(endpoint(providerSetting, params), messages, params)

    private fun endpoint(setting: ProviderSetting.Grok, params: TextGenerationParams) = ResponsesEndpoint(
        url = "$XAI_BASE_URL/responses",
        capabilities = GROK_CAPABILITIES,
        configureRequest = {
            configureAuth(setting, params.customHeaders)
            // Chat Completions 的写法，Responses 以请求体里的 prompt_cache_key 为准，这里一并带上
            params.sessionId?.let { header("x-grok-conv-id", it) }
        },
    )

    private suspend fun getJson(setting: ProviderSetting.Grok, url: String, grokCli: Boolean = false): JsonObject {
        val request = Request.Builder()
            .url(url)
            .apply { configureAuth(setting, grokCli = grokCli) }
            .get()
            .build()
        return json.parseToJsonElement(client.fetchBody(request)).jsonObject
    }

    private suspend fun Request.Builder.configureAuth(
        setting: ProviderSetting.Grok,
        customHeaders: List<CustomHeader> = emptyList(),
        grokCli: Boolean = false,
    ) = configureAccountAuth(credentials, setting, customHeaders) {
        // Grok CLI 的代理要求带上，和官方 CLI 走同一个产品通道
        if (grokCli) header("X-XAI-Token-Auth", "xai-grok-cli")
    }
}

/**
 * 合并两个模型目录，两边都有的以 Grok CLI 的为准。
 *
 * 目录里声明了推理档位的模型才算推理模型，只有它们会带上 `reasoning.effort`；
 * 其余模型（包括自带推理但不接受档位的）都按普通模型请求。
 */
internal fun parseGrokModels(cliCatalog: JsonObject?, apiCatalog: JsonObject?): List<Model> {
    val models = linkedMapOf<String, Model>()
    listOfNotNull(cliCatalog, apiCatalog).forEach { catalog ->
        (catalog["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { row ->
            val meta = row["_meta"] as? JsonObject
            // Grok CLI 目录里的 id 可能和推理用的模型 id 不同
            val id = row.string("model") ?: row.string("modelId") ?: row.string("id")
                ?: meta?.string("model") ?: meta?.string("modelId") ?: return@forEach
            val hidden = (row["hidden"] ?: meta?.get("hidden"))?.jsonPrimitiveOrNull?.booleanOrNull == true
            if (hidden || NON_CHAT_MODEL.containsMatchIn(id) || id in models) return@forEach
            val efforts = (row["reasoningEfforts"] ?: row["reasoning_efforts"] ?: meta?.get("reasoningEfforts"))
                as? JsonArray
            val contextWindow = listOf(
                row["contextWindow"],
                row["context_window"],
                meta?.get("contextWindow"),
                meta?.get("totalContextTokens")
            ).firstNotNullOfOrNull { it?.number()?.toInt() }
            models[id] = Model(
                modelId = id,
                displayName = row.string("name") ?: row.string("displayName") ?: row.string("display_name")
                    ?: meta?.string("displayName") ?: id,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                abilities = if (efforts.isNullOrEmpty()) {
                    listOf(ModelAbility.TOOL)
                } else {
                    listOf(ModelAbility.TOOL, ModelAbility.REASONING)
                },
                contextLength = contextWindow ?: Model().contextLength,
            )
        }
    }
    return models.values.toList()
}

/** `GET /billing?format=credits`：SuperGrok 的按周额度，不是这种账号时返回 null */
internal fun parseGrokWeeklyUsage(body: JsonObject, now: Instant = Clock.System.now()): ProviderQuota.UsageLimits? {
    val config = body["config"] as? JsonObject ?: return null
    val period = config["currentPeriod"] as? JsonObject ?: return null
    val start = period.instant("start") ?: return null
    val end = period.instant("end") ?: return null
    if (end <= start || period.string("type")?.uppercase()?.contains("WEEK") != true) return null
    // 新一周还没有用量时不返回 creditUsagePercent
    val percent = config["creditUsagePercent"]?.number() ?: if (end > now) 0.0 else return null
    return ProviderQuota.UsageLimits(
        windows = listOf(
            UsageWindow(usedPercent = percent.coerceIn(0.0, 100.0), windowMinutes = WEEK_MINUTES, resetsAt = end)
        ),
        limitReached = percent >= 100,
    )
}

/** `GET /billing`：统一计费账号的按月额度，没有额度时返回 null */
internal fun parseGrokMonthlyUsage(body: JsonObject): ProviderQuota.UsageLimits? {
    val config = body["config"] as? JsonObject ?: return null
    val start = config.instant("billingPeriodStart") ?: return null
    val end = config.instant("billingPeriodEnd") ?: return null
    val limit = (config["monthlyLimit"] as? JsonObject)?.get("val")?.number()
    val used = (config["used"] as? JsonObject)?.get("val")?.number()
    if (end <= start || limit == null || limit <= 0 || used == null) return null
    return ProviderQuota.UsageLimits(
        windows = listOf(
            UsageWindow(
                usedPercent = (used / limit * 100).coerceIn(0.0, 100.0),
                // 自然月长短不一，按接口给的账期算
                windowMinutes = (end - start).inWholeMinutes.toInt(),
                resetsAt = end,
            )
        ),
        limitReached = used >= limit,
    )
}

private fun JsonObject.isUnifiedBilling(): Boolean =
    (get("config") as? JsonObject)?.get("isUnifiedBillingUser")?.jsonPrimitiveOrNull?.booleanOrNull == true

private fun JsonObject.string(key: String): String? = stringOrNull(key)?.takeIf { it.isNotBlank() }

// 数字可能以字符串给出
private fun JsonElement.number(): Double? = jsonPrimitiveOrNull?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.instant(key: String): Instant? = string(
    key
)?.let { runCatching { Instant.parse(it) }.getOrNull() }
