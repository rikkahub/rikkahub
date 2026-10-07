package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private const val SETTINGS_STORE_NAME = "settings"

// 读取失败时的最大重试次数
private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

// 进程内单例, 同一文件只能存在一个 DataStore 实例
private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

private fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            // 文件已损坏无法解析, 先留一份原文件用于排查/抢救, 再重建为空
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
        ),
        produceFile = { file },
    )
}

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
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
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
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")

        // 媒体生成
        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")

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

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings, launchCount: Int) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings, launchCount)
        }

        // launchCount 只在恢复备份时传入，和设置在同一次写入里落盘
        private suspend fun persistSettings(
            dataStore: DataStore<Preferences>,
            settings: Settings,
            launchCount: Int? = null,
        ) {
            dataStore.edit { preferences ->
                if (launchCount != null) preferences[LAUNCH_COUNT] = launchCount
                preferences[DYNAMIC_COLOR] = settings.dynamicColor
                preferences[THEME_ID] = settings.themeId
                preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
                preferences[DEVELOPER_MODE] = settings.developerMode
                preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
                preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

                preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
                preferences[SELECT_MODEL] = settings.chatModelId.toString()
                preferences[FAST_MODEL] = settings.fastModelId.toString()
                preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
                preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
                preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
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
                preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))

                preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
                preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
                preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
                preferences[UPLOAD_S3_CONFIG] = JsonInstant.encodeToString(settings.uploadS3Config)
                preferences[MEDIA_GENERATION_PROVIDERS] = JsonInstant.encodeToString(settings.mediaGenerationProviders)
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
                preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            }
        }
    }

    private val dataStore = context.settingsStore

    // 读取失败时绝不能回退为空配置, 否则默认值会被当成用户数据写回, 覆盖全部设置
    // 偶发 IO 错误重试, 仍失败则向上抛出 (文件损坏由 corruptionHandler 处理)
    private val preferencesFlow = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }

    val settingsFlowRaw = preferencesFlow
        // 启动次数不属于 Settings，只有它变化时不用重新解码
        .distinctUntilChanged { old, new -> old.asMap() - LAUNCH_COUNT == new.asMap() - LAUNCH_COUNT }
        .map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
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
                networkSetting = JsonInstant.decodeFromString(preferences[NETWORK_SETTING] ?: "{}"),
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
                uploadS3Config = preferences[UPLOAD_S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                mediaGenerationProviders = preferences[MEDIA_GENERATION_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
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
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .map { it.withBuiltInDefaults() }
        .map { it.withoutInvalidReferences() }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }
        // 整份设置的 JSON 解码很重，收集方多在主线程，不能让它们各自在自己的线程上解码
        .flowOn(Dispatchers.Default)

    // 启动次数每次启动都会变，不放进 Settings，否则刚进入应用所有读取设置的界面就要重组一遍
    val launchCountFlow: Flow<Int> = preferencesFlow
        .map { preferences -> preferences[LAUNCH_COUNT] ?: 0 }
        .distinctUntilChanged()

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        // 整份设置的比较也留在后台线程
        .flowOn(Dispatchers.Default)
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        persistSettings(dataStore, settings)
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    // 只原子地修改单个 key, 不能用 update() 写回整份快照
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun setLaunchCount(count: Int) {
        dataStore.edit { preferences -> preferences[LAUNCH_COUNT] = count }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
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
}
