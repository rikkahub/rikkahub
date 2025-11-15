package me.rerere.rikkahub.service.speech

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.api.speech.SpeechServiceFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.speechServicesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "speech_services_preferences")

/**
 * Manages preferences for speech-to-text (STT) and text-to-speech (TTS) services.
 */
class SpeechServicesPreferences(private val context: Context) {

    private val dataStore = context.speechServicesDataStore

    @Serializable
    data class TtsHttpConfig(
        val urlTemplate: String,
        val apiKey: String, // Keep apiKey for header-based auth
        val headers: Map<String, String>,
        val httpMethod: String = "GET", // HTTP方法：GET 或 POST
        val requestBody: String = "", // POST请求的body模板，支持占位符如{text}
        val contentType: String = "application/json", // POST请求的Content-Type
        val voiceId: String = "", // 特定于TTS提供商的音色ID
        val modelName: String = "" // TTS模型名称（用于SiliconFlow等）
    )

    companion object {
        // TTS Preference Keys
        val TTS_SERVICE_TYPE = stringPreferencesKey("tts_service_type")
        val TTS_HTTP_CONFIG = stringPreferencesKey("tts_http_config")
        val TTS_CLEANER_REGEXS = stringSetPreferencesKey("tts_cleaner_regexs")

        // STT Preference Keys
        val STT_SERVICE_TYPE = stringPreferencesKey("stt_service_type")
        val STT_HTTP_CONFIG = stringPreferencesKey("stt_http_config")

        // Default Values
        val DEFAULT_STT_SERVICE_TYPE = SpeechServiceFactory.SpeechServiceType.SHERPA_NCNN

        // HTTP TTS的默认预设
        val DEFAULT_HTTP_TTS_PRESET = TtsHttpConfig(
            urlTemplate = "",
            apiKey = "",
            headers = emptyMap(),
            httpMethod = "GET",
            requestBody = "",
            contentType = "application/json",
            voiceId = "",
            modelName = ""
        )
    }

    // --- STT Flows ---
    val sttServiceTypeFlow: Flow<SpeechServiceFactory.SpeechServiceType> = dataStore.data.map { prefs ->
        SpeechServiceFactory.SpeechServiceType.valueOf(
            prefs[STT_SERVICE_TYPE] ?: DEFAULT_STT_SERVICE_TYPE.name
        )
    }

    /** 只保存 TTS 清理正则列表 */
    suspend fun saveTtsCleanerRegexs(regexs: List<String>) {
        dataStore.edit { prefs ->
            prefs[TTS_CLEANER_REGEXS] = regexs.filter { it.isNotBlank() }.toSet()
        }
    }

    // --- Save STT Settings ---
    suspend fun saveSttSettings(
        serviceType: SpeechServiceFactory.SpeechServiceType
    ) {
        dataStore.edit { prefs ->
            prefs[STT_SERVICE_TYPE] = serviceType.name
        }
    }
}
