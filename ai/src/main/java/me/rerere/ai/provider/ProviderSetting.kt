package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.util.ApiKeyConfig
import me.rerere.ai.util.KeyManagementConfig
import me.rerere.ai.util.activeKeys
import me.rerere.ai.util.parseLegacyApiKeys
import me.rerere.ai.util.serializeToLegacyApiKey
import kotlin.uuid.Uuid

@Serializable
data class BalanceOption(
    val enabled: Boolean = false,
    val apiPath: String = "/credits",
    val resultPath: String = "data.total_usage",
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption
    abstract val apiKeys: List<ApiKeyConfig>
    abstract val keyManagement: KeyManagementConfig

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        apiKeys: List<ApiKeyConfig> = this.apiKeys,
        keyManagement: KeyManagementConfig = this.keyManagement,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    /**
     * 获取有效的 API Key 列表
     * 优先使用结构化 apiKeys，若为空则从 apiKey 字符串解析
     */
    fun getEffectiveApiKeys(): List<ApiKeyConfig> {
        return if (apiKeys.isNotEmpty()) {
            apiKeys.activeKeys()
        } else {
            parseLegacyApiKeys(getLegacyApiKey())
        }
    }

    /** 同步结构化 keys 到旧的 apiKey 字符串（向后兼容序列化） */
    abstract fun syncApiKeyString()

    /**
     * 从旧的 apiKey 字符串同步到结构化 apiKeys（反向同步）
     * 编辑旧字段时自动触发，保持双向兼容
     */
    fun syncApiKeysFromSource(): ProviderSetting {
        val raw = getLegacyApiKey()
        if (raw.isBlank()) return this
        val parsedKeys = parseLegacyApiKeys(raw)
        if (parsedKeys.isEmpty()) return this
        val existingMap = apiKeys.associateBy { it.id }
        val mergedKeys = parsedKeys.map { parsedKey ->
            existingMap[parsedKey.id]?.let { existing ->
                existing.copy(
                    key = parsedKey.key,
                    name = existing.name.ifBlank { parsedKey.name },
                )
            } ?: parsedKey
        }
        val updated = copyProvider(apiKeys = mergedKeys)
        updated.syncApiKeyString()
        return updated
    }

    /** 获取旧格式的 apiKey 字符串 */
    abstract fun getLegacyApiKey(): String

    /** 设置旧格式的 apiKey 字符串并自动解析 */
    abstract fun setLegacyApiKey(key: String)

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        // OpenRouter only: emit per-block cache_control breakpoints. Anthropic/Gemini/Qwen
        // need them explicitly; auto-caching providers have the field stripped upstream, so
        // it is applied to every model on the OpenRouter host. See ChatCompletionsAPI.
        var promptCaching: Boolean = true,
        var includeHistoryReasoning: Boolean = true,
        // OpenRouter only: provider-routing preferences emitted as the `provider` object.
        var routing: OpenRouterRouting = OpenRouterRouting(),
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = apiKey
        override fun setLegacyApiKey(key: String) { apiKey = key }
        override fun syncApiKeyString() {
            apiKey = if (apiKeys.isNotEmpty()) {
                serializeToLegacyApiKey(apiKeys)
            } else {
                ""
            }
        }

        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id, enabled = enabled, name = name, models = models,
                builtIn = builtIn, description = description,
                balanceOption = balanceOption, shortDescription = shortDescription,
                apiKeys = apiKeys, keyManagement = keyManagement,
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "",
        var serviceAccountEmail: String = "",
        var location: String = "us-central1",
        var projectId: String = "",
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = apiKey
        override fun setLegacyApiKey(key: String) { apiKey = key }
        override fun syncApiKeyString() {
            apiKey = if (apiKeys.isNotEmpty()) {
                serializeToLegacyApiKey(apiKeys)
            } else {
                ""
            }
        }

        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id, enabled = enabled, name = name, models = models,
                builtIn = builtIn, description = description,
                balanceOption = balanceOption, shortDescription = shortDescription,
                apiKeys = apiKeys, keyManagement = keyManagement,
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = true,  // ~10% input rate on cache hits, near-pure win
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = apiKey
        override fun setLegacyApiKey(key: String) { apiKey = key }
        override fun syncApiKeyString() {
            apiKey = if (apiKeys.isNotEmpty()) {
                serializeToLegacyApiKey(apiKeys)
            } else {
                ""
            }
        }

        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid, enabled: Boolean, name: String, models: List<Model>,
            balanceOption: BalanceOption, apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig, builtIn: Boolean,
            description: @Composable (() -> Unit), shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id, enabled = enabled, name = name, models = models,
                balanceOption = balanceOption, builtIn = builtIn,
                description = description, shortDescription = shortDescription,
                apiKeys = apiKeys, keyManagement = keyManagement,
            )
        }
    }

    @Serializable
    @SerialName("aicore")
    data class AICore(
        override var id: Uuid = AICORE_PROVIDER_ID,
        override var enabled: Boolean = true,
        override var name: String = "AICore (on-device)",
        override var models: List<Model> = AICORE_DEFAULT_MODELS,
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        // Defaults to PREVIEW because the STABLE feature ID is missing on most current
        // AICore beta channels — PREVIEW is what actually resolves to a working model on
        // Pixel 8/9/10 today. Users can flip back to STABLE once Google promotes it.
        var releaseStage: AICoreReleaseStage = AICoreReleaseStage.PREVIEW,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = this // synthetic models, no add
        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model else it })
        }

        override fun delModel(model: Model): ProviderSetting = this // synthetic models, no delete

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val m = removeAt(from)
                add(to, m)
            })
        }

        override fun getLegacyApiKey(): String = ""
        override fun setLegacyApiKey(key: String) {}
        override fun syncApiKeyString() {}

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                apiKeys = apiKeys,
                keyManagement = keyManagement,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption,
            )
        }
    }

    @Serializable
    @SerialName("local_litert")
    data class LiteRtLocal(
        override var id: Uuid = LITERT_PROVIDER_ID,
        override var enabled: Boolean = false,
        override var name: String = "Local · LiteRT",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = ""
        override fun setLegacyApiKey(key: String) {}
        override fun syncApiKeyString() {}
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model else it })
        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting =
            copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            apiKeys = apiKeys, keyManagement = keyManagement,
            builtIn = builtIn, description = description, shortDescription = shortDescription,
            balanceOption = balanceOption,
        )
    }

    @Serializable
    @SerialName("codex")
    data class Codex(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Codex",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override var apiKeys: List<ApiKeyConfig> = emptyList(),
        override var keyManagement: KeyManagementConfig = KeyManagementConfig(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun getLegacyApiKey(): String = ""
        override fun setLegacyApiKey(key: String) {}
        override fun syncApiKeyString() {}
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            apiKeys: List<ApiKeyConfig>,
            keyManagement: KeyManagementConfig,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                apiKeys = apiKeys,
                keyManagement = keyManagement,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    companion object {
        // Types presented to the user when adding / converting a provider. AICore is
        // intentionally NOT in this list: it is a singleton built-in (one per device,
        // synthesized from the AICore system app), so the "type segmented row" inside
        // the Add-Provider dialog and ProviderConfigure should not offer it as a choice.
        // Including it overflowed the dialog width and wrapped the OpenAI / Google labels
        // onto two lines on a Pixel 10 Pro.
        val Types by lazy {
            listOf(OpenAI::class, Google::class, Claude::class)
        }
    }
}

@Serializable
enum class AICoreReleaseStage { STABLE, PREVIEW }

// Stable IDs for the synthetic AICore provider + models so saved settings and
// conversations referencing them survive app re-installs and provider re-seeds.
val AICORE_PROVIDER_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000001")
val LITERT_PROVIDER_ID: Uuid = Uuid.parse("11111111-aaaa-bbbb-cccc-000000000002")
private val AICORE_NANO_FAST_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000002")
private val AICORE_NANO_FULL_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000003")

val AICORE_NANO_FAST_MODEL: Model = Model(
    id = AICORE_NANO_FAST_ID,
    modelId = "nano-fast",
    displayName = "Gemini Nano (FAST)",
    abilities = listOf(ModelAbility.TOOL),
)

val AICORE_NANO_FULL_MODEL: Model = Model(
    id = AICORE_NANO_FULL_ID,
    modelId = "nano-full",
    displayName = "Gemini Nano (FULL)",
    abilities = listOf(ModelAbility.TOOL),
)

val AICORE_DEFAULT_MODELS: List<Model> = listOf(AICORE_NANO_FAST_MODEL, AICORE_NANO_FULL_MODEL)
