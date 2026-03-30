package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.tools.termux.DEFAULT_TIMEOUT_MS
import me.rerere.rikkahub.data.ai.tools.termux.TERMUX_PTY_DEFAULT_MAX_OUTPUT_CHARS
import me.rerere.rikkahub.data.ai.tools.termux.TERMUX_PTY_DEFAULT_SERVER_PORT
import me.rerere.rikkahub.data.ai.tools.termux.TERMUX_PTY_DEFAULT_YIELD_TIME_MS
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.DEFAULT_TEXT_SELECTION_ACTIONS
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookGlobalSettings
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import me.rerere.rikkahub.data.model.SillyTavernPreset
import me.rerere.rikkahub.data.model.SillyTavernPromptTemplate
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.TextSelectionConfig
import me.rerere.rikkahub.data.model.UserPersonaProfile
import me.rerere.rikkahub.data.model.normalizeStPresetState
import me.rerere.rikkahub.data.model.normalizedForSystemPromptSupplement
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

const val DEFAULT_COMPRESS_TARGET_TOKENS = 2000
const val DEFAULT_COMPRESS_KEEP_RECENT_MESSAGES = 32

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
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
        val CUSTOM_THEME_SETTING = stringPreferencesKey("custom_theme_setting")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
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
        val COMPRESS_TARGET_TOKENS = intPreferencesKey("compress_target_tokens")
        val COMPRESS_KEEP_RECENT_MESSAGES = intPreferencesKey("compress_keep_recent_messages")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        val SCHEDULED_TASKS = stringPreferencesKey("scheduled_tasks")
        val SCHEDULED_TASK_KEEP_ALIVE_ENABLED = booleanPreferencesKey("scheduled_task_keep_alive_enabled")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // Termux
        val TERMUX_WORKDIR = stringPreferencesKey("termux_workdir")
        val TERMUX_NEEDS_APPROVAL = booleanPreferencesKey("termux_needs_approval")
        val TERMUX_APPROVAL_BLACKLIST = stringPreferencesKey("termux_approval_blacklist")
        val TERMUX_TIMEOUT_MS = longPreferencesKey("termux_timeout_ms")
        val TERMUX_WORKDIR_SERVER_ENABLED = booleanPreferencesKey("termux_workdir_server_enabled")
        val TERMUX_WORKDIR_SERVER_PORT = intPreferencesKey("termux_workdir_server_port")
        val TERMUX_COMMAND_MODE_ENABLED = booleanPreferencesKey("termux_command_mode_enabled")
        val TERMUX_PTY_INTERACTIVE_ENABLED = booleanPreferencesKey("termux_pty_interactive_enabled")
        val TERMUX_PTY_SERVER_PORT = intPreferencesKey("termux_pty_server_port")
        val TERMUX_PTY_YIELD_TIME_MS = longPreferencesKey("termux_pty_yield_time_ms")
        val TERMUX_PTY_MAX_OUTPUT_CHARS = intPreferencesKey("termux_pty_max_output_chars")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val LOREBOOK_GLOBAL_SETTINGS = stringPreferencesKey("lorebook_global_settings")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")
        val REGEXES = stringPreferencesKey("regexes")
        val ST_PRESET_ENABLED = booleanPreferencesKey("st_preset_enabled")
        val ST_PRESET_TEMPLATE = stringPreferencesKey("st_preset_template")
        val ST_PRESETS = stringPreferencesKey("st_presets")
        val SELECTED_ST_PRESET = stringPreferencesKey("selected_st_preset")
        val USER_PERSONA_PROFILES = stringPreferencesKey("user_persona_profiles")
        val SELECTED_USER_PERSONA_PROFILE = stringPreferencesKey("selected_user_persona_profile")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // Android 文本选择集成
        val TEXT_SELECTION_CONFIG = stringPreferencesKey("text_selection_config")
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
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] == true,
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                compressTargetTokens = (preferences[COMPRESS_TARGET_TOKENS] ?: DEFAULT_COMPRESS_TARGET_TOKENS)
                    .coerceAtLeast(1),
                compressKeepRecentMessages =
                    (preferences[COMPRESS_KEEP_RECENT_MESSAGES] ?: DEFAULT_COMPRESS_KEEP_RECENT_MESSAGES)
                        .coerceAtLeast(0),
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemeSetting = preferences[CUSTOM_THEME_SETTING]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: CustomThemeSetting(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                scheduledTasks = preferences[SCHEDULED_TASKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                scheduledTaskKeepAliveEnabled = preferences[SCHEDULED_TASK_KEEP_ALIVE_ENABLED] == true,
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
                termuxWorkdir = preferences[TERMUX_WORKDIR] ?: "/data/data/com.termux/files/home",
                termuxNeedsApproval = preferences[TERMUX_NEEDS_APPROVAL] != false,
                termuxApprovalBlacklist = preferences[TERMUX_APPROVAL_BLACKLIST].orEmpty(),
                termuxTimeoutMs = (preferences[TERMUX_TIMEOUT_MS] ?: DEFAULT_TIMEOUT_MS).coerceAtLeast(1_000L),
                termuxWorkdirServerEnabled = preferences[TERMUX_WORKDIR_SERVER_ENABLED] == true,
                termuxWorkdirServerPort = preferences[TERMUX_WORKDIR_SERVER_PORT] ?: 9090,
                termuxCommandModeEnabled = preferences[TERMUX_COMMAND_MODE_ENABLED] == true,
                termuxPtyInteractiveEnabled = preferences[TERMUX_PTY_INTERACTIVE_ENABLED] != false,
                termuxPtyServerPort =
                    (preferences[TERMUX_PTY_SERVER_PORT] ?: TERMUX_PTY_DEFAULT_SERVER_PORT).coerceIn(1024, 65535),
                termuxPtyYieldTimeMs = (preferences[TERMUX_PTY_YIELD_TIME_MS] ?: TERMUX_PTY_DEFAULT_YIELD_TIME_MS)
                    .coerceAtLeast(0L),
                termuxPtyMaxOutputChars =
                    (preferences[TERMUX_PTY_MAX_OUTPUT_CHARS] ?: TERMUX_PTY_DEFAULT_MAX_OUTPUT_CHARS)
                        .coerceAtLeast(256),
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
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebookGlobalSettings = preferences[LOREBOOK_GLOBAL_SETTINGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: LorebookGlobalSettings(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                regexes = preferences[REGEXES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                stPresetEnabled = preferences[ST_PRESET_ENABLED] == true,
                stPresetTemplate = preferences[ST_PRESET_TEMPLATE]?.let {
                    JsonInstant.decodeFromString(it)
                },
                stPresets = preferences[ST_PRESETS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedStPresetId = preferences[SELECTED_ST_PRESET]?.let { Uuid.parse(it) },
                userPersonaProfiles = preferences[USER_PERSONA_PROFILES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedUserPersonaProfileId = preferences[SELECTED_USER_PERSONA_PROFILE]?.let { Uuid.parse(it) },
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                textSelectionConfig = preferences[TEXT_SELECTION_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: TextSelectionConfig(),
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
            var assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            var scheduledTasks = it.scheduledTasks
            if (scheduledTasks.isEmpty()) {
                val migratedTasks = assistants.flatMap { assistant ->
                    assistant.scheduledPromptTasks.map { task ->
                        task.copy(
                            assistantId = assistant.id,
                            lastRunId = null
                        )
                    }
                }
                if (migratedTasks.isNotEmpty()) {
                    scheduledTasks = migratedTasks
                    assistants = assistants.map { assistant ->
                        assistant.copy(scheduledPromptTasks = emptyList())
                    }.toMutableList()
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            var normalizedSettings = it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
                scheduledTasks = scheduledTasks,
            )
            normalizedSettings
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validAssistantIds = settings.assistants.map { it.id }.toSet()
            val fallbackAssistantId = settings.assistants.firstOrNull()?.id ?: DEFAULT_ASSISTANT_ID
            val maxSearchIndex = (settings.searchServices.size - 1).coerceAtLeast(0)
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val userPersonaProfiles = settings.userPersonaProfiles.distinctBy { it.id }
            val selectedUserPersonaProfileId = userPersonaProfiles
                .firstOrNull { it.id == settings.selectedUserPersonaProfileId }
                ?.id
                ?: userPersonaProfiles.firstOrNull()?.id
            val textSelectionConfig = settings.textSelectionConfig.let { config ->
                config.copy(
                    assistantId = config.assistantId?.takeIf { it in validAssistantIds },
                    actions = config.actions
                        .ifEmpty { DEFAULT_TEXT_SELECTION_ACTIONS }
                        .distinctBy { it.id }
                )
            }
            val normalizedStSettings = settings.normalizeStPresetState()
            normalizedStSettings.copy(
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
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections
                    .map { it.normalizedForSystemPromptSupplement() }
                    .distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                regexes = normalizedStSettings.regexes.distinctBy { it.id },
                stPresets = normalizedStSettings.stPresets,
                selectedStPresetId = normalizedStSettings.selectedStPresetId,
                stPresetTemplate = normalizedStSettings.stPresetTemplate,
                userPersonaProfiles = userPersonaProfiles,
                selectedUserPersonaProfileId = selectedUserPersonaProfileId,
                scheduledTasks = settings.scheduledTasks
                    .distinctBy { it.id }
                    .map { task ->
                        task.copy(
                            assistantId = if (task.assistantId in validAssistantIds) {
                                task.assistantId
                            } else {
                                fallbackAssistantId
                            },
                            overrideMcpServers = task.overrideMcpServers?.filter { it in validMcpServerIds }?.toSet(),
                            overrideSearchServiceIndex = task.overrideSearchServiceIndex?.let { index ->
                                if (settings.searchServices.isEmpty()) null else index.coerceIn(0, maxSearchIndex)
                            },
                        )
                    },
                textSelectionConfig = textSelectionConfig,
                quickMessages = settings.quickMessages.distinctBy { it.id },
                lorebookGlobalSettings = settings.lorebookGlobalSettings.normalized(),
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if (settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        val normalizedStSettings = settings.normalizeStPresetState()
        val normalizedSettings = normalizedStSettings.copy(
            modeInjections = settings.modeInjections.map { it.normalizedForSystemPromptSupplement() },
            lorebookGlobalSettings = settings.lorebookGlobalSettings.normalized(),
            stPresets = normalizedStSettings.stPresets,
            selectedStPresetId = normalizedStSettings.selectedStPresetId,
            stPresetTemplate = normalizedStSettings.stPresetTemplate,
            regexes = normalizedStSettings.regexes.distinctBy { it.id },
        )
        val previousSettings = settingsFlow.value
        if (!previousSettings.init) {
            val removedAvatarUris = previousSettings.referencedLocalUserAvatarUris() - normalizedSettings.referencedLocalUserAvatarUris()
            if (removedAvatarUris.isNotEmpty()) {
                get<FilesManager>().deleteChatFiles(removedAvatarUris.map { it.toUri() })
            }
        }
        settingsFlow.value = normalizedSettings
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = normalizedSettings.dynamicColor
            preferences[THEME_ID] = normalizedSettings.themeId
            preferences[CUSTOM_THEME_SETTING] = JsonInstant.encodeToString(normalizedSettings.customThemeSetting)
            preferences[DEVELOPER_MODE] = normalizedSettings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(normalizedSettings.displaySetting)

            preferences[ENABLE_WEB_SEARCH] = normalizedSettings.enableWebSearch
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(normalizedSettings.favoriteModels)
            preferences[SELECT_MODEL] = normalizedSettings.chatModelId.toString()
            preferences[TITLE_MODEL] = normalizedSettings.titleModelId.toString()
            preferences[TRANSLATE_MODEL] = normalizedSettings.translateModeId.toString()
            preferences[SUGGESTION_MODEL] = normalizedSettings.suggestionModelId.toString()
            preferences[IMAGE_GENERATION_MODEL] = normalizedSettings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = normalizedSettings.titlePrompt
            preferences[TRANSLATION_PROMPT] = normalizedSettings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = normalizedSettings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = normalizedSettings.suggestionPrompt
            preferences[OCR_MODEL] = normalizedSettings.ocrModelId.toString()
            preferences[OCR_PROMPT] = normalizedSettings.ocrPrompt
            preferences[COMPRESS_MODEL] = normalizedSettings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = normalizedSettings.compressPrompt
            preferences[COMPRESS_TARGET_TOKENS] = normalizedSettings.compressTargetTokens.coerceAtLeast(1)
            preferences[COMPRESS_KEEP_RECENT_MESSAGES] = normalizedSettings.compressKeepRecentMessages.coerceAtLeast(0)

            preferences[PROVIDERS] = JsonInstant.encodeToString(normalizedSettings.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(normalizedSettings.assistants)
            preferences[SELECT_ASSISTANT] = normalizedSettings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(normalizedSettings.assistantTags)
            preferences[SCHEDULED_TASKS] = JsonInstant.encodeToString(normalizedSettings.scheduledTasks)
            preferences[SCHEDULED_TASK_KEEP_ALIVE_ENABLED] = normalizedSettings.scheduledTaskKeepAliveEnabled

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(normalizedSettings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(normalizedSettings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = normalizedSettings.searchServiceSelected.coerceIn(0, normalizedSettings.searchServices.size - 1)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(normalizedSettings.mcpServers)
            preferences[TERMUX_WORKDIR] = normalizedSettings.termuxWorkdir
            preferences[TERMUX_NEEDS_APPROVAL] = normalizedSettings.termuxNeedsApproval
            preferences[TERMUX_APPROVAL_BLACKLIST] = normalizedSettings.termuxApprovalBlacklist
            preferences[TERMUX_TIMEOUT_MS] = normalizedSettings.termuxTimeoutMs.coerceAtLeast(1_000L)
            preferences[TERMUX_WORKDIR_SERVER_ENABLED] = normalizedSettings.termuxWorkdirServerEnabled
            preferences[TERMUX_WORKDIR_SERVER_PORT] = normalizedSettings.termuxWorkdirServerPort
            preferences[TERMUX_COMMAND_MODE_ENABLED] = normalizedSettings.termuxCommandModeEnabled
            preferences[TERMUX_PTY_INTERACTIVE_ENABLED] = normalizedSettings.termuxPtyInteractiveEnabled
            preferences[TERMUX_PTY_SERVER_PORT] = normalizedSettings.termuxPtyServerPort.coerceIn(1024, 65535)
            preferences[TERMUX_PTY_YIELD_TIME_MS] = normalizedSettings.termuxPtyYieldTimeMs.coerceAtLeast(0L)
            preferences[TERMUX_PTY_MAX_OUTPUT_CHARS] = normalizedSettings.termuxPtyMaxOutputChars.coerceAtLeast(256)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(normalizedSettings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(normalizedSettings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(normalizedSettings.ttsProviders)
            preferences[SELECTED_TTS_PROVIDER] = normalizedSettings.selectedTTSProviderId.toString()
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(normalizedSettings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(normalizedSettings.lorebooks)
            preferences[LOREBOOK_GLOBAL_SETTINGS] = JsonInstant.encodeToString(normalizedSettings.lorebookGlobalSettings)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(normalizedSettings.quickMessages)
            preferences[REGEXES] = JsonInstant.encodeToString(normalizedSettings.regexes)
            preferences[ST_PRESET_ENABLED] = normalizedSettings.stPresetEnabled
            normalizedSettings.stPresetTemplate?.let {
                preferences[ST_PRESET_TEMPLATE] = JsonInstant.encodeToString(it)
            } ?: preferences.remove(ST_PRESET_TEMPLATE)
            preferences[ST_PRESETS] = JsonInstant.encodeToString(normalizedSettings.stPresets)
            normalizedSettings.selectedStPresetId?.let {
                preferences[SELECTED_ST_PRESET] = it.toString()
            } ?: preferences.remove(SELECTED_ST_PRESET)
            preferences[USER_PERSONA_PROFILES] = JsonInstant.encodeToString(normalizedSettings.userPersonaProfiles)
            normalizedSettings.selectedUserPersonaProfileId?.let {
                preferences[SELECTED_USER_PERSONA_PROFILE] = it.toString()
            } ?: preferences.remove(SELECTED_USER_PERSONA_PROFILE)
            preferences[WEB_SERVER_ENABLED] = normalizedSettings.webServerEnabled
            preferences[WEB_SERVER_PORT] = normalizedSettings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = normalizedSettings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = normalizedSettings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = normalizedSettings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(normalizedSettings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = normalizedSettings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = normalizedSettings.sponsorAlertDismissedAt
            preferences[TEXT_SELECTION_CONFIG] = JsonInstant.encodeToString(normalizedSettings.textSelectionConfig)
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
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

    suspend fun updateAssistantThinkingBudget(assistantId: Uuid, thinkingBudget: Int?) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(thinkingBudget = thinkingBudget)
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

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemeSetting: CustomThemeSetting = CustomThemeSetting(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val compressTargetTokens: Int = DEFAULT_COMPRESS_TARGET_TOKENS,
    val compressKeepRecentMessages: Int = DEFAULT_COMPRESS_KEEP_RECENT_MESSAGES,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val scheduledTasks: List<ScheduledPromptTask> = emptyList(),
    val scheduledTaskKeepAliveEnabled: Boolean = false,
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val termuxWorkdir: String = "/data/data/com.termux/files/home",
    val termuxNeedsApproval: Boolean = true,
    val termuxApprovalBlacklist: String = "",
    val termuxTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val termuxWorkdirServerEnabled: Boolean = false,
    val termuxWorkdirServerPort: Int = 9090,
    val termuxCommandModeEnabled: Boolean = false,
    val termuxPtyInteractiveEnabled: Boolean = true,
    val termuxPtyServerPort: Int = TERMUX_PTY_DEFAULT_SERVER_PORT,
    val termuxPtyYieldTimeMs: Long = TERMUX_PTY_DEFAULT_YIELD_TIME_MS,
    val termuxPtyMaxOutputChars: Int = TERMUX_PTY_DEFAULT_MAX_OUTPUT_CHARS,
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val lorebookGlobalSettings: LorebookGlobalSettings = LorebookGlobalSettings(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
    val stPresetEnabled: Boolean = false,
    val stPresetTemplate: SillyTavernPromptTemplate? = null,
    val stPresets: List<SillyTavernPreset> = emptyList(),
    val selectedStPresetId: Uuid? = null,
    val userPersonaProfiles: List<UserPersonaProfile> = emptyList(),
    val selectedUserPersonaProfileId: Uuid? = null,
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    val textSelectionConfig: TextSelectionConfig = TextSelectionConfig(),
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

private fun Settings.referencedLocalUserAvatarUris(): Set<String> = buildSet {
    fun addAvatar(avatar: Avatar) {
        if (avatar is Avatar.Image && avatar.url.startsWith("file:")) {
            add(avatar.url)
        }
    }

    addAvatar(displaySetting.userAvatar)
    userPersonaProfiles.forEach { profile ->
        addAvatar(profile.avatar)
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
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateBelowName: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = false,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableToolApprovalNotification: Boolean = true,
    val enableScheduledTaskNotification: Boolean = true,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val enableCodeBlockRichRender: Boolean = true,
    val codeBlockRenderMaxDepth: Int = 0,
    val ttsOnlyReadQuoted: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

fun DisplaySetting.shouldRenderCodeBlock(messageDepthFromEnd: Int?): Boolean {
    val effectiveMaxDepth = codeBlockRenderMaxDepth.takeIf { it > 0 } ?: return true
    val depth = messageDepthFromEnd ?: return true
    return depth <= effectiveMaxDepth
}

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

fun Settings.findModelById(uuid: Uuid): Model? {
    return this.providers.findModelById(uuid)
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
    return ttsProviders.find { it.id == selectedTTSProviderId } ?: ttsProviders.firstOrNull()
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
            - Time: {{cur_datetime}}
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
