package me.rerere.rikkahub.voiceagent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.AndroidVoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.OkHttpGeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileApi
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

interface ManagedVoiceCallSession {
    val state: StateFlow<VoiceAgentUiState>
    fun start()
    fun interrupt()
    fun setMuted(value: Boolean)
    fun reconnect()
    fun recordDiagnostic(name: String, detail: String)
    fun end()
    suspend fun endAndDrain()
    fun closeNow()
}

interface VoiceAgentCallFactory {
    fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession
}

class DefaultVoiceAgentCallFactory(
    private val context: Context,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) : VoiceAgentCallFactory {
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession {
        val voiceLabApi = VoiceLabMobileApi(
            baseUrl = config.voiceLabBaseUrl,
            credentials = config.credentials,
        )
        return VoiceAgentCallSession(
            modelId = config.voiceModelId,
            sessionApi = VoiceLabVoiceSessionApi(api = voiceLabApi),
            toolApi = VoiceLabHermesToolApi(api = voiceLabApi),
            gemini = OkHttpGeminiLiveVoiceClient(httpClient = okHttpClient),
            audio = AndroidVoiceAudioEngine(context = context),
            conversationStore = ChatServiceVoiceConversationStore(
                conversationId = conversationId,
                chatService = chatService,
            ),
            contextProvider = SettingsVoiceAgentContextProvider(
                settingsStore = settingsStore,
                voiceModelName = config.voiceModelId,
            ),
            scope = scope,
        )
    }
}
