package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.providers.AnthropicVoiceASRController
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun rememberCustomAsrState(): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val asrState = remember {
        CustomAsrStateImpl(context.applicationContext, httpClient)
    }

    // Key only on the resolved Claude OAuth fallback token, not the whole provider
    // list: editing an unrelated chat provider must not tear down an in-progress
    // recording. The fallback is only read when the selected ASR provider is
    // AnthropicVoice with a blank token, so re-resolving on its change is sufficient.
    val claudeOAuthFallback = resolveClaudeOAuthFallback(settings)

    DisposableEffect(
        settings.selectedASRProviderId,
        settings.asrProviders,
        claudeOAuthFallback
    ) {
        asrState.updateProvider(settings.getSelectedASRProvider(), settings)
        onDispose { }
    }

    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }

    return asrState
}

interface CustomAsrState {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    fun cleanup()
}

private class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient
) : CustomAsrState {
    private var controller: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .build()

    override val state: StateFlow<ASRState>
        get() = controller?.state ?: idleState

    fun updateProvider(provider: ASRProviderSetting?, settings: Settings) {
        controller?.dispose()
        controller = provider?.let { createController(it, settings) }
        if (controller == null) {
            idleState.value = ASRState()
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit) {
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            controller?.start(onTranscriptChange)
        }
    }

    override fun stop() {
        controller?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun cleanup() {
        controller?.dispose()
        controller = null
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun createController(provider: ASRProviderSetting, settings: Settings): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) return null
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) return null
                DashScopeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) return null
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.AnthropicVoice -> {
                val token = provider.oauthToken.ifBlank { resolveClaudeOAuthFallback(settings) }
                if (token.isBlank()) return null
                AnthropicVoiceASRController(context, httpClient, provider, token)
            }
        }
    }
}

// First Claude provider configured for OAuth with a non-blank token, used as the
// AnthropicVoice ASR fallback credential when the ASR provider has no token of its own.
internal fun resolveClaudeOAuthFallback(settings: Settings): String =
    settings.providers
        .filterIsInstance<ProviderSetting.Claude>()
        .firstOrNull { it.authType == ClaudeAuthType.OAuth && it.oauthToken.isNotBlank() }
        ?.oauthToken
        .orEmpty()
