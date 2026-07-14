package me.rerere.rikkahub.data.sync.cloud

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * Keys that may be synced via entity_type=setting.
 * Device-local secrets / connection config are intentionally excluded
 * (perryDeviceToken, perryConfig, webdav/s3, etc.).
 *
 * Providers sync with Perry device-token apiKeys stripped; each device rebinds
 * Perry gateways to its own token + local Perry host on apply.
 */
object SyncableSettings {
    const val THEME_ID = "theme_id"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val DISPLAY_SETTING = "display_setting"
    const val FAVORITE_MODELS = "favorite_models"
    const val CHAT_MODEL = "chat_model"
    const val TITLE_MODEL = "title_model"
    const val TRANSLATE_MODEL = "translate_model"
    const val ENABLE_SUGGESTION = "enable_suggestion"
    const val SUGGESTION_MODEL = "suggestion_model"
    const val TITLE_PROMPT = "title_prompt"
    const val TRANSLATION_PROMPT = "translation_prompt"
    const val SUGGESTION_PROMPT = "suggestion_prompt"
    const val OCR_MODEL = "ocr_model"
    const val OCR_PROMPT = "ocr_prompt"
    const val COMPRESS_MODEL = "compress_model"
    const val COMPRESS_PROMPT = "compress_prompt"
    const val ASSISTANT_TAGS = "assistant_tags"
    const val QUICK_MESSAGES = "quick_messages"
    const val MODE_INJECTIONS = "mode_injections"
    const val LOREBOOKS = "lorebooks"
    const val SELECT_ASSISTANT = "select_assistant"
    const val PROVIDERS = "providers"

    val ALL_KEYS: Set<String> = setOf(
        THEME_ID,
        DYNAMIC_COLOR,
        DISPLAY_SETTING,
        FAVORITE_MODELS,
        CHAT_MODEL,
        TITLE_MODEL,
        TRANSLATE_MODEL,
        ENABLE_SUGGESTION,
        SUGGESTION_MODEL,
        TITLE_PROMPT,
        TRANSLATION_PROMPT,
        SUGGESTION_PROMPT,
        OCR_MODEL,
        OCR_PROMPT,
        COMPRESS_MODEL,
        COMPRESS_PROMPT,
        ASSISTANT_TAGS,
        QUICK_MESSAGES,
        MODE_INJECTIONS,
        LOREBOOKS,
        SELECT_ASSISTANT,
        PROVIDERS,
    )

    fun extract(settings: Settings): Map<String, JsonElement> {
        return mapOf(
            THEME_ID to JsonPrimitive(settings.themeId),
            DYNAMIC_COLOR to JsonPrimitive(settings.dynamicColor),
            DISPLAY_SETTING to toJson(settings.displaySetting),
            FAVORITE_MODELS to toJson(settings.favoriteModels),
            CHAT_MODEL to JsonPrimitive(settings.chatModelId.toString()),
            TITLE_MODEL to (settings.titleModelId?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            TRANSLATE_MODEL to JsonPrimitive(settings.translateModeId.toString()),
            ENABLE_SUGGESTION to JsonPrimitive(settings.enableSuggestion),
            SUGGESTION_MODEL to (
                settings.suggestionModelId?.let { JsonPrimitive(it.toString()) } ?: JsonNull
            ),
            TITLE_PROMPT to JsonPrimitive(settings.titlePrompt),
            TRANSLATION_PROMPT to JsonPrimitive(settings.translatePrompt),
            SUGGESTION_PROMPT to JsonPrimitive(settings.suggestionPrompt),
            OCR_MODEL to JsonPrimitive(settings.ocrModelId.toString()),
            OCR_PROMPT to JsonPrimitive(settings.ocrPrompt),
            COMPRESS_MODEL to JsonPrimitive(settings.compressModelId.toString()),
            COMPRESS_PROMPT to JsonPrimitive(settings.compressPrompt),
            ASSISTANT_TAGS to toJson(settings.assistantTags),
            QUICK_MESSAGES to toJson(settings.quickMessages),
            MODE_INJECTIONS to toJson(settings.modeInjections),
            LOREBOOKS to toJson(settings.lorebooks),
            SELECT_ASSISTANT to JsonPrimitive(settings.assistantId.toString()),
            PROVIDERS to toJson(settings.providers.map { sanitizeProviderForSync(it) }),
        )
    }

    fun applyKey(settings: Settings, key: String, value: JsonElement): Settings {
        return when (key) {
            THEME_ID -> settings.copy(themeId = value.asStringOrNull() ?: settings.themeId)
            DYNAMIC_COLOR -> settings.copy(
                dynamicColor = value.asBooleanOrNull() ?: settings.dynamicColor
            )
            DISPLAY_SETTING -> settings.copy(displaySetting = fromJson(value))
            FAVORITE_MODELS -> settings.copy(favoriteModels = fromJson(value))
            CHAT_MODEL -> settings.copy(chatModelId = value.asUuidOrNull() ?: settings.chatModelId)
            TITLE_MODEL -> settings.copy(titleModelId = value.asUuidOrNull())
            TRANSLATE_MODEL -> settings.copy(
                translateModeId = value.asUuidOrNull() ?: settings.translateModeId
            )
            ENABLE_SUGGESTION -> settings.copy(
                enableSuggestion = value.asBooleanOrNull() ?: settings.enableSuggestion
            )
            SUGGESTION_MODEL -> settings.copy(suggestionModelId = value.asUuidOrNull())
            TITLE_PROMPT -> settings.copy(titlePrompt = value.asStringOrNull() ?: settings.titlePrompt)
            TRANSLATION_PROMPT -> settings.copy(
                translatePrompt = value.asStringOrNull() ?: settings.translatePrompt
            )
            SUGGESTION_PROMPT -> settings.copy(
                suggestionPrompt = value.asStringOrNull() ?: settings.suggestionPrompt
            )
            OCR_MODEL -> settings.copy(ocrModelId = value.asUuidOrNull() ?: settings.ocrModelId)
            OCR_PROMPT -> settings.copy(ocrPrompt = value.asStringOrNull() ?: settings.ocrPrompt)
            COMPRESS_MODEL -> settings.copy(
                compressModelId = value.asUuidOrNull() ?: settings.compressModelId
            )
            COMPRESS_PROMPT -> settings.copy(
                compressPrompt = value.asStringOrNull() ?: settings.compressPrompt
            )
            ASSISTANT_TAGS -> settings.copy(assistantTags = fromJson(value))
            QUICK_MESSAGES -> settings.copy(quickMessages = fromJson(value))
            MODE_INJECTIONS -> settings.copy(modeInjections = fromJson(value))
            LOREBOOKS -> settings.copy(lorebooks = fromJson(value))
            SELECT_ASSISTANT -> settings.copy(
                assistantId = value.asUuidOrNull() ?: settings.assistantId
            )
            PROVIDERS -> {
                val remote = runCatching {
                    fromJson<List<ProviderSetting>>(value)
                }.getOrElse { return settings }
                val merged = mergeRemoteProviders(local = settings.providers, remote = remote)
                val rebound = rebindProviderCredentials(merged, settings)
                settings.copy(providers = rebound)
            }
            else -> settings
        }
    }

    /**
     * Strip device-local Perry tokens before upload. Upstream API keys for
     * non-Perry providers are kept so multi-device personal use works.
     */
    fun sanitizeProviderForSync(provider: ProviderSetting): ProviderSetting {
        return when (provider) {
            is ProviderSetting.OpenAI -> {
                val models = provider.models.map { sanitizeModelForSync(it) }
                if (PerryCatalog.isPerryGateway(provider)) {
                    provider.copy(apiKey = "", models = models)
                } else {
                    provider.copy(models = models)
                }
            }
            is ProviderSetting.Google -> provider.copy(
                models = provider.models.map { sanitizeModelForSync(it) },
            )
            is ProviderSetting.Claude -> provider.copy(
                models = provider.models.map { sanitizeModelForSync(it) },
            )
        }
    }

    fun sanitizeModelForSync(model: Model): Model {
        val overwrite = model.providerOverwrite ?: return model
        return model.copy(providerOverwrite = sanitizeProviderForSync(overwrite))
    }

    /**
     * Server list is authoritative by provider id order from remote.
     * Preserve local secrets when remote value is blank (e.g. Perry token stripped).
     */
    fun mergeRemoteProviders(
        local: List<ProviderSetting>,
        remote: List<ProviderSetting>,
    ): List<ProviderSetting> {
        val localById = local.associateBy { it.id }
        return remote.map { remoteProvider ->
            val localProvider = localById[remoteProvider.id]
            preserveLocalSecrets(remoteProvider, localProvider)
        }
    }

    fun preserveLocalSecrets(
        remote: ProviderSetting,
        local: ProviderSetting?,
    ): ProviderSetting {
        return when (remote) {
            is ProviderSetting.OpenAI -> {
                val localOpen = local as? ProviderSetting.OpenAI
                if (PerryCatalog.isPerryGateway(remote)) {
                    // Token always rebound later; keep empty or local until rebind.
                    remote.copy(
                        apiKey = localOpen?.apiKey.orEmpty().ifBlank { remote.apiKey },
                        models = remote.models.map { m ->
                            m.copy(
                                providerOverwrite = m.providerOverwrite?.let { ow ->
                                    preserveLocalSecrets(ow, null)
                                },
                            )
                        },
                    )
                } else {
                    remote.copy(
                        apiKey = remote.apiKey.ifBlank { localOpen?.apiKey.orEmpty() },
                        models = remote.models.map { m ->
                            m.copy(
                                providerOverwrite = m.providerOverwrite?.let { ow ->
                                    preserveLocalSecrets(ow, null)
                                },
                            )
                        },
                    )
                }
            }
            is ProviderSetting.Google -> {
                val localG = local as? ProviderSetting.Google
                remote.copy(
                    apiKey = remote.apiKey.ifBlank { localG?.apiKey.orEmpty() },
                    privateKey = remote.privateKey.ifBlank { localG?.privateKey.orEmpty() },
                    models = remote.models.map { m ->
                        m.copy(
                            providerOverwrite = m.providerOverwrite?.let { ow ->
                                preserveLocalSecrets(ow, null)
                            },
                        )
                    },
                )
            }
            is ProviderSetting.Claude -> {
                val localC = local as? ProviderSetting.Claude
                remote.copy(
                    apiKey = remote.apiKey.ifBlank { localC?.apiKey.orEmpty() },
                    models = remote.models.map { m ->
                        m.copy(
                            providerOverwrite = m.providerOverwrite?.let { ow ->
                                preserveLocalSecrets(ow, null)
                            },
                        )
                    },
                )
            }
        }
    }

    /** Rebind Perry gateways to this device's token and Perry host. */
    fun rebindProviderCredentials(
        providers: List<ProviderSetting>,
        settings: Settings,
    ): List<ProviderSetting> {
        val token = settings.perryDeviceToken
        val perryBase = runCatching {
            if (settings.perryConfig.isConfigured()) settings.perryConfig.normalizedBaseUrl() else null
        }.getOrNull()
        return providers.map { provider ->
            when (provider) {
                is ProviderSetting.OpenAI -> {
                    if (!PerryCatalog.isPerryGateway(provider)) return@map provider
                    val monelId = PerryCatalog.monelProviderIdFromBaseUrl(provider.baseUrl)
                    val baseUrl = if (perryBase != null && monelId != null) {
                        PerryApiClient.join(perryBase, "/v1/ai/$monelId/v1")
                    } else {
                        provider.baseUrl
                    }
                    provider.copy(
                        baseUrl = baseUrl,
                        apiKey = token.ifBlank { provider.apiKey },
                        useResponseApi = false,
                    )
                }
                else -> provider
            }
        }
    }

    private inline fun <reified T> toJson(value: T): JsonElement =
        JsonInstant.encodeToJsonElement(value)

    private inline fun <reified T> fromJson(value: JsonElement): T =
        JsonInstant.decodeFromJsonElement(value)

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.content

    private fun JsonElement.asBooleanOrNull(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: primitive.content.toBooleanStrictOrNull()
    }

    private fun JsonElement.asUuidOrNull(): Uuid? {
        if (this is JsonNull) return null
        val raw = (this as? JsonPrimitive)?.content ?: return null
        return runCatching { Uuid.parse(raw) }.getOrNull()
    }
}

fun assistantMutationPayload(assistantJson: JsonElement, name: String): JsonElement {
    return buildJsonObject {
        put("name", JsonPrimitive(name))
        put("payload", assistantJson)
    }
}
