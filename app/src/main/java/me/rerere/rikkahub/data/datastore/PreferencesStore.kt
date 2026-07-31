package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.hasSameFrozenConnectionIdentity
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV5Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"
private const val SETTINGS_REVISION_HISTORY_LIMIT = 128

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
            PreferenceStoreV5Migration(),
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] != false,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            val (searchServices, searchServiceSelected) = SearchServiceOptions.migrateDisabledCustomJs(
                settings.searchServices,
                settings.searchServiceSelected,
            )
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
                searchServices = searchServices,
                searchServiceSelected = searchServiceSelected,
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    private val updateMutex = Mutex()
    private val revisionState = SettingsRevisionState()
    private val authoritativeSettings: Settings
        get() = revisionState.current
    val settingsFlow = revisionState.flow

    init {
        scope.launch {
            settingsFlowRaw.distinctUntilChanged().collect { emitted ->
                updateMutex.withLock {
                    when {
                        authoritativeSettings.init -> {
                            publishAuthoritative(emitted)
                        }
                        else -> Unit // Every post-initialization write is published by persistSettings.
                    }
                }
            }
        }
    }

    suspend fun update(settings: Settings) {
        revisionState.awaitReady()
        updateMutex.withLock {
            val merged = revisionState.prepareWholeUpdate(settings)
            if (settings.revision != authoritativeSettings.revision) {
                Log.d(TAG, "Merged stale whole-settings update from revision ${settings.revision}")
            }
            persistSettings(merged)
        }
    }

    /** Explicit full replacement for restore/import paths that do not originate from [settingsFlow]. */
    suspend fun replace(settings: Settings) {
        revisionState.awaitReady()
        updateMutex.withLock {
            persistSettings(settings)
        }
    }

    private suspend fun persistSettings(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        withContext(NonCancellable) {
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            settings.suggestionModelId?.let {
                preferences[SUGGESTION_MODEL] = it.toString()
            } ?: preferences.remove(SUGGESTION_MODEL)
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
        }
            // Re-read through the same migration/normalization pipeline used at startup so memory
            // and disk cannot diverge after a whole-settings write.
            publishAuthoritative(settingsFlowRaw.first())
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        revisionState.awaitReady()
        updateMutex.withLock {
            persistSettings(fn(authoritativeSettings))
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        update { settings -> settings.copy(assistantId = assistantId) }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private fun publishAuthoritative(settings: Settings) {
        revisionState.publish(settings)
    }
}

/**
 * Owns the revision watermark and the bounded history needed to merge UI snapshots that were
 * captured before a preceding write completed. Calls that mutate this state are serialized by
 * [SettingsStore.updateMutex]; [awaitReady] is safe to call before taking that mutex.
 */
internal class SettingsRevisionState(
    private val historyLimit: Int = SETTINGS_REVISION_HISTORY_LIMIT,
) {
    init {
        require(historyLimit > 0) { "Settings revision history must not be empty" }
    }

    @Volatile
    var current: Settings = Settings.dummy()
        private set

    private var revision = 0L
    private val history = LinkedHashMap<Long, Settings>()
    val flow = MutableStateFlow(current)

    suspend fun awaitReady() {
        if (current.init) {
            flow.first { settings -> !settings.init }
        }
    }

    fun prepareWholeUpdate(incoming: Settings): Settings {
        check(!current.init) { "Settings are not initialized" }
        require(!incoming.init) { "Dummy settings cannot be persisted" }
        return when {
            incoming.revision == current.revision -> incoming
            incoming.revision > current.revision -> {
                throw IllegalArgumentException(
                    "Settings revision ${incoming.revision} is newer than ${current.revision}"
                )
            }

            else -> {
                val base = history[incoming.revision]
                    ?: throw IllegalStateException(
                        "Settings revision ${incoming.revision} is no longer available for merge"
                    )
                mergeStaleSettings(base = base, incoming = incoming, current = current)
            }
        }
    }

    fun publish(settings: Settings) {
        require(!settings.init) { "Dummy settings cannot be authoritative" }
        check(revision < Long.MAX_VALUE) { "Settings revision exhausted" }
        val stamped = settings.copy(revision = ++revision)
        current = stamped
        history[stamped.revision] = stamped
        while (history.size > historyLimit) {
            history.remove(history.keys.first())
        }
        flow.value = stamped
    }
}

/** Applies only the fields changed by [incoming] since [base] onto [current]. */
internal fun mergeStaleSettings(
    base: Settings,
    incoming: Settings,
    current: Settings,
): Settings = current.copy(
    dynamicColor = mergeChanged(base.dynamicColor, incoming.dynamicColor, current.dynamicColor),
    themeId = mergeChanged(base.themeId, incoming.themeId, current.themeId),
    customThemes = mergeStableSerializableList(
        base.customThemes,
        incoming.customThemes,
        current.customThemes,
        CustomTheme.serializer(),
        CustomTheme::id,
    ),
    developerMode = mergeChanged(base.developerMode, incoming.developerMode, current.developerMode),
    displaySetting = mergeSerializableValue(
        base.displaySetting,
        incoming.displaySetting,
        current.displaySetting,
        DisplaySetting.serializer(),
    ),
    favoriteModels = mergeIdentityList(base.favoriteModels, incoming.favoriteModels, current.favoriteModels),
    chatModelId = mergeChanged(base.chatModelId, incoming.chatModelId, current.chatModelId),
    fastModelId = mergeChanged(base.fastModelId, incoming.fastModelId, current.fastModelId),
    titleModelId = mergeChanged(base.titleModelId, incoming.titleModelId, current.titleModelId),
    imageGenerationModelId = mergeChanged(
        base.imageGenerationModelId,
        incoming.imageGenerationModelId,
        current.imageGenerationModelId,
    ),
    titlePrompt = mergeChanged(base.titlePrompt, incoming.titlePrompt, current.titlePrompt),
    translateModeId = mergeChanged(base.translateModeId, incoming.translateModeId, current.translateModeId),
    translatePrompt = mergeChanged(base.translatePrompt, incoming.translatePrompt, current.translatePrompt),
    translateThinkingBudget = mergeChanged(
        base.translateThinkingBudget,
        incoming.translateThinkingBudget,
        current.translateThinkingBudget,
    ),
    enableSuggestion = mergeChanged(
        base.enableSuggestion,
        incoming.enableSuggestion,
        current.enableSuggestion,
    ),
    suggestionModelId = mergeChanged(
        base.suggestionModelId,
        incoming.suggestionModelId,
        current.suggestionModelId,
    ),
    suggestionPrompt = mergeChanged(base.suggestionPrompt, incoming.suggestionPrompt, current.suggestionPrompt),
    ocrModelId = mergeChanged(base.ocrModelId, incoming.ocrModelId, current.ocrModelId),
    ocrPrompt = mergeChanged(base.ocrPrompt, incoming.ocrPrompt, current.ocrPrompt),
    compressModelId = mergeChanged(base.compressModelId, incoming.compressModelId, current.compressModelId),
    compressPrompt = mergeChanged(base.compressPrompt, incoming.compressPrompt, current.compressPrompt),
    assistantId = mergeChanged(base.assistantId, incoming.assistantId, current.assistantId),
    providers = mergeStaleProviders(base.providers, incoming.providers, current.providers),
    assistants = mergeStableSerializableList(
        base.assistants,
        incoming.assistants,
        current.assistants,
        Assistant.serializer(),
        Assistant::id,
    ),
    assistantTags = mergeStableSerializableList(
        base.assistantTags,
        incoming.assistantTags,
        current.assistantTags,
        Tag.serializer(),
        Tag::id,
    ),
    searchServices = mergeStableSerializableList(
        base.searchServices,
        incoming.searchServices,
        current.searchServices,
        SearchServiceOptions.serializer(),
        SearchServiceOptions::id,
    ),
    searchCommonOptions = mergeSerializableValue(
        base.searchCommonOptions,
        incoming.searchCommonOptions,
        current.searchCommonOptions,
        SearchCommonOptions.serializer(),
    ),
    searchServiceSelected = mergeChanged(
        base.searchServiceSelected,
        incoming.searchServiceSelected,
        current.searchServiceSelected,
    ),
    mcpServers = mergeStaleMcpServers(base.mcpServers, incoming.mcpServers, current.mcpServers),
    webDavConfig = mergeSerializableValue(
        base.webDavConfig,
        incoming.webDavConfig,
        current.webDavConfig,
        WebDavConfig.serializer(),
    ),
    s3Config = mergeSerializableValue(
        base.s3Config,
        incoming.s3Config,
        current.s3Config,
        S3Config.serializer(),
    ),
    ttsProviders = mergeStableSerializableList(
        base.ttsProviders,
        incoming.ttsProviders,
        current.ttsProviders,
        TTSProviderSetting.serializer(),
        TTSProviderSetting::id,
    ),
    selectedTTSProviderId = mergeChanged(
        base.selectedTTSProviderId,
        incoming.selectedTTSProviderId,
        current.selectedTTSProviderId,
    ),
    asrProviders = mergeStableSerializableList(
        base.asrProviders,
        incoming.asrProviders,
        current.asrProviders,
        ASRProviderSetting.serializer(),
        ASRProviderSetting::id,
    ),
    selectedASRProviderId = mergeChanged(
        base.selectedASRProviderId,
        incoming.selectedASRProviderId,
        current.selectedASRProviderId,
    ),
    modeInjections = mergeStableSerializableList(
        base.modeInjections,
        incoming.modeInjections,
        current.modeInjections,
        PromptInjection.ModeInjection.serializer(),
        PromptInjection.ModeInjection::id,
    ),
    lorebooks = mergeStableSerializableList(
        base.lorebooks,
        incoming.lorebooks,
        current.lorebooks,
        Lorebook.serializer(),
        Lorebook::id,
    ),
    quickMessages = mergeStableSerializableList(
        base.quickMessages,
        incoming.quickMessages,
        current.quickMessages,
        QuickMessage.serializer(),
        QuickMessage::id,
    ),
    webServerEnabled = mergeChanged(
        base.webServerEnabled,
        incoming.webServerEnabled,
        current.webServerEnabled,
    ),
    webServerPort = mergeChanged(base.webServerPort, incoming.webServerPort, current.webServerPort),
    webServerJwtEnabled = mergeChanged(
        base.webServerJwtEnabled,
        incoming.webServerJwtEnabled,
        current.webServerJwtEnabled,
    ),
    webServerAccessPassword = mergeChanged(
        base.webServerAccessPassword,
        incoming.webServerAccessPassword,
        current.webServerAccessPassword,
    ),
    webServerLocalhostOnly = mergeChanged(
        base.webServerLocalhostOnly,
        incoming.webServerLocalhostOnly,
        current.webServerLocalhostOnly,
    ),
    backupReminderConfig = mergeSerializableValue(
        base.backupReminderConfig,
        incoming.backupReminderConfig,
        current.backupReminderConfig,
        BackupReminderConfig.serializer(),
    ),
    launchCount = mergeChanged(base.launchCount, incoming.launchCount, current.launchCount),
    sponsorAlertDismissedAt = mergeChanged(
        base.sponsorAlertDismissedAt,
        incoming.sponsorAlertDismissedAt,
        current.sponsorAlertDismissedAt,
    ),
)

private fun <T> mergeChanged(base: T, incoming: T, current: T): T =
    if (incoming != base) incoming else current

/**
 * Applies the membership/order delta from [incoming] while retaining independent additions,
 * removals, reorders, and item edits already present in [current].
 */
private fun <T : Any, K> mergeStableList(
    base: List<T>,
    incoming: List<T>,
    current: List<T>,
    idOf: (T) -> K,
    equivalent: (T, T) -> Boolean,
    mergeItem: (base: T, incoming: T, current: T) -> T,
): List<T> {
    val incomingUnchanged = base.size == incoming.size && base.zip(incoming).all { (left, right) ->
        idOf(left) == idOf(right) && equivalent(left, right)
    }
    if (incomingUnchanged) return current

    val baseById = base.associateBy(idOf)
    val incomingById = incoming.associateBy(idOf)
    val currentById = current.associateBy(idOf)
    val baseIds = base.map(idOf)
    val incomingIds = incoming.map(idOf)
    val currentIds = current.map(idOf)
    val orderedIds = LinkedHashSet<K>().apply {
        addAll(if (incomingIds == baseIds) currentIds else incomingIds)
        addAll(incomingIds.filter { it !in baseById })
        addAll(currentIds.filter { it !in baseById })
    }

    return orderedIds.mapNotNull { id ->
        val baseItem = baseById[id]
        val incomingItem = incomingById[id]
        val currentItem = currentById[id]
        when {
            baseItem == null -> currentItem ?: incomingItem
            incomingItem == null -> null // The stale writer explicitly removed this original item.
            currentItem == null -> null // A newer removal must not be resurrected by a stale edit.
            equivalent(incomingItem, baseItem) -> currentItem
            else -> mergeItem(baseItem, incomingItem, currentItem)
        }
    }
}

private fun <T : Any> mergeIdentityList(base: List<T>, incoming: List<T>, current: List<T>): List<T> =
    mergeStableList(
        base = base,
        incoming = incoming,
        current = current,
        idOf = { it },
        equivalent = { left, right -> left == right },
        mergeItem = { _, changed, _ -> changed },
    )

private fun <T : Any, K> mergeStableSerializableList(
    base: List<T>,
    incoming: List<T>,
    current: List<T>,
    serializer: KSerializer<T>,
    idOf: (T) -> K,
): List<T> = mergeStableList(
    base = base,
    incoming = incoming,
    current = current,
    idOf = idOf,
    equivalent = { left, right -> serializedValue(left, serializer) == serializedValue(right, serializer) },
    mergeItem = { baseItem, incomingItem, currentItem ->
        mergeSerializableValue(baseItem, incomingItem, currentItem, serializer)
    },
)

private fun mergeStaleProviders(
    base: List<ProviderSetting>,
    incoming: List<ProviderSetting>,
    current: List<ProviderSetting>,
): List<ProviderSetting> = mergeStableList(
    base = base,
    incoming = incoming,
    current = current,
    idOf = ProviderSetting::id,
    equivalent = { left, right ->
        serializedValue(left, ProviderSetting.serializer()) ==
            serializedValue(right, ProviderSetting.serializer())
    },
    mergeItem = { baseItem, incomingItem, currentItem ->
        val merged = mergeSerializableValue(
            baseItem,
            incomingItem,
            currentItem,
            ProviderSetting.serializer(),
        )
        val transientSource = currentItem.takeIf { it::class == merged::class }
            ?: incomingItem.takeIf { it::class == merged::class }
            ?: merged
        merged.copyProvider(
            builtIn = transientSource.builtIn,
            description = transientSource.description,
            shortDescription = transientSource.shortDescription,
        )
    },
)

private fun <T : Any> mergeSerializableValue(
    base: T,
    incoming: T,
    current: T,
    serializer: KSerializer<T>,
): T {
    val baseJson = serializedValue(base, serializer)
    val incomingJson = serializedValue(incoming, serializer)
    val currentJson = serializedValue(current, serializer)
    if (incomingJson == baseJson) return current
    if (currentJson == baseJson) return incoming

    // A transport/provider kind change is an authority change. Never synthesize a hybrid subtype.
    if (incoming::class != base::class) return incoming
    if (current::class != base::class) return current

    val merged = checkNotNull(mergeJsonValue(baseJson, incomingJson, currentJson))
    return JsonInstant.decodeFromJsonElement(serializer, merged)
}

private fun <T> serializedValue(value: T, serializer: KSerializer<T>): JsonElement =
    JsonInstant.encodeToJsonElement(serializer, value)

/** Null means a missing object key; [kotlinx.serialization.json.JsonNull] remains a real value. */
private fun mergeJsonValue(
    base: JsonElement?,
    incoming: JsonElement?,
    current: JsonElement?,
): JsonElement? {
    if (incoming == base) return current
    if (current == base) return incoming
    if (base == null) return current ?: incoming // Concurrent additions with the same key prefer authority.
    if (incoming == null || current == null) return null

    return when {
        base is JsonObject && incoming is JsonObject && current is JsonObject -> {
            val keys = LinkedHashSet<String>().apply {
                addAll(incoming.keys)
                addAll(current.keys)
                addAll(base.keys)
            }
            JsonObject(
                buildMap {
                    keys.forEach { key ->
                        mergeJsonValue(base[key], incoming[key], current[key])?.let { put(key, it) }
                    }
                }
            )
        }

        base is JsonArray && incoming is JsonArray && current is JsonArray -> {
            mergeJsonArray(base, incoming, current)
        }

        else -> incoming // Same leaf changed on both sides: the explicit stale write wins.
    }
}

private fun mergeJsonArray(base: JsonArray, incoming: JsonArray, current: JsonArray): JsonArray {
    if (listOf(base, incoming, current).all { stableJsonObjectIds(it) != null }) {
        val merged = mergeStableList(
            base = base,
            incoming = incoming,
            current = current,
            idOf = { element -> ((element as JsonObject).getValue("id") as JsonPrimitive).toString() },
            equivalent = { left, right -> left == right },
            mergeItem = { baseItem, incomingItem, currentItem ->
                mergeJsonValue(baseItem, incomingItem, currentItem) ?: incomingItem
            },
        )
        return JsonArray(merged)
    }

    val allUniquePrimitives = listOf(base, incoming, current).all { array ->
        array.all { it is JsonPrimitive } && array.distinct().size == array.size
    }
    return if (allUniquePrimitives) {
        JsonArray(mergeIdentityList(base, incoming, current))
    } else {
        incoming
    }
}

private fun stableJsonObjectIds(array: JsonArray): List<String>? {
    val ids = array.map { element ->
        val objectValue = element as? JsonObject ?: return null
        val id = objectValue["id"] as? JsonPrimitive ?: return null
        id.toString()
    }
    return ids.takeIf { it.distinct().size == it.size }
}

/**
 * Merges MCP servers by stable id. A current deletion wins over a stale edit, while servers added
 * after the stale snapshot are retained. OAuth bearer material is reconciled separately below.
 */
internal fun mergeStaleMcpServers(
    base: List<McpServerConfig>,
    incoming: List<McpServerConfig>,
    current: List<McpServerConfig>,
): List<McpServerConfig> {
    if (incoming == base) return current

    val baseById = base.associateBy(McpServerConfig::id)
    val incomingById = incoming.associateBy(McpServerConfig::id)
    val currentById = current.associateBy(McpServerConfig::id)
    val orderedIds = buildList {
        addAll(incoming.map(McpServerConfig::id))
        addAll(current.asSequence().filter { it.id !in baseById }.map(McpServerConfig::id))
    }.distinct()

    return orderedIds.mapNotNull { id ->
        val incomingServer = incomingById[id] ?: return@mapNotNull null
        val baseServer = baseById[id]
        val currentServer = currentById[id]
        when {
            baseServer == null && currentServer == null -> incomingServer
            baseServer == null -> currentServer // Concurrent id collision: keep the established authority.
            currentServer == null -> null // A server removed after the snapshot must not be resurrected.
            incomingServer == baseServer -> currentServer
            else -> mergeStaleMcpServer(baseServer, incomingServer, currentServer)
        }
    }
}

private data class OAuthMerge(
    val state: McpOAuthState?,
    val explicitCredentialClear: Boolean = false,
)

private fun mergeStaleMcpServer(
    base: McpServerConfig,
    incoming: McpServerConfig,
    current: McpServerConfig,
): McpServerConfig {
    val oauthMerge = mergeStaleOAuth(
        base = base.commonOptions.oauth,
        incoming = incoming.commonOptions.oauth,
        current = current.commonOptions.oauth,
    )
    val commonOptions = McpCommonOptions(
        enable = mergeChanged(
            base.commonOptions.enable,
            incoming.commonOptions.enable,
            current.commonOptions.enable,
        ),
        name = mergeChanged(
            base.commonOptions.name,
            incoming.commonOptions.name,
            current.commonOptions.name,
        ),
        headers = mergeChanged(
            base.commonOptions.headers,
            incoming.commonOptions.headers,
            current.commonOptions.headers,
        ),
        tools = mergeChanged(
            base.commonOptions.tools,
            incoming.commonOptions.tools,
            current.commonOptions.tools,
        ),
        oauth = oauthMerge.state,
    )
    val transportTemplate = if (incoming::class != base::class) incoming else current
    val mergedUrl = mergeChanged(base.serverUrl, incoming.serverUrl, current.serverUrl)
    val provisional = when (transportTemplate) {
        is McpServerConfig.SseTransportServer -> transportTemplate.copy(
            id = base.id,
            commonOptions = commonOptions,
            url = mergedUrl,
        )

        is McpServerConfig.StreamableHTTPServer -> transportTemplate.copy(
            id = base.id,
            commonOptions = commonOptions,
            url = mergedUrl,
        )
    }
    val selectedOAuth = provisional.commonOptions.oauth ?: return provisional
    val safeOAuth = when {
        oauthMerge.explicitCredentialClear -> selectedOAuth
        hasSameFrozenConnectionIdentity(provisional, current) -> {
            val latest = current.commonOptions.oauth
            selectedOAuth.copy(
                accessToken = latest?.accessToken,
                refreshToken = latest?.refreshToken,
                expiresAt = latest?.expiresAt ?: 0L,
            )
        }

        hasSameFrozenConnectionIdentity(provisional, base) -> selectedOAuth
        else -> selectedOAuth.withoutCredentials()
    }
    return provisional.clone(commonOptions = provisional.commonOptions.copy(oauth = safeOAuth))
}

private fun mergeStaleOAuth(
    base: McpOAuthState?,
    incoming: McpOAuthState?,
    current: McpOAuthState?,
): OAuthMerge {
    if (incoming == base) return OAuthMerge(current)
    if (base != null && current == null) return OAuthMerge(null)
    if (base.hasCredentials() && incoming != null && !incoming.hasCredentials()) {
        return OAuthMerge(incoming, explicitCredentialClear = true)
    }

    val incomingDefinition = incoming?.withoutCredentials()
    val baseDefinition = base?.withoutCredentials()
    return if (incomingDefinition == baseDefinition) {
        // Credential-only changes from a stale whole snapshot may contain an obsolete bearer.
        OAuthMerge(current)
    } else {
        OAuthMerge(incoming)
    }
}

private fun McpOAuthState?.hasCredentials(): Boolean =
    this != null && (!accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank())

private fun McpOAuthState.withoutCredentials(): McpOAuthState = copy(
    accessToken = null,
    refreshToken = null,
    expiresAt = 0L,
)

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    @Transient
    val revision: Long = 0L,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = true,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
