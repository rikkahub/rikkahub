package me.rerere.rikkahub.voiceagent

import android.content.Context
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.AndroidVoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.OkHttpGeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileApi
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

class VoiceAgentViewModelFactory(
    private val context: Context,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) {
    fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
    ): VoiceAgentViewModel {
        val voiceLabApi = VoiceLabMobileApi(
            baseUrl = config.voiceLabBaseUrl,
            credentials = config.credentials,
        )
        return VoiceAgentViewModel(
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
        )
    }
}
