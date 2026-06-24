package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.GroqTTSProvider
import me.rerere.tts.provider.providers.MiMoTTSProvider
import me.rerere.tts.provider.providers.MiniMaxTTSProvider
import me.rerere.tts.provider.providers.OpenAITTSProvider
import me.rerere.tts.provider.providers.QwenTTSProvider
import me.rerere.tts.provider.providers.SystemTTSProvider
import me.rerere.tts.provider.providers.XAITTSProvider
import kotlin.reflect.KClass

/**
 * Registry of TTS providers, keyed by the [TTSProviderSetting] subtype each one drives. This replaces
 * the previous "one manager field + one when-arm per provider" dispatcher: adding a TTS provider is now
 * a single registry entry (plus its setting subtype + the [TTSProviderSetting.Types] picker entry),
 * not three edit sites that must stay in sync.
 *
 * Providers are stateless instances (the per-call [Context] is passed to `generateSpeech`), so a single
 * shared instance per type is safe — the same lifetime the old manager fields had.
 *
 * Exhaustiveness moves from the compiler's `when` check to TTSProviderRegistryTest, which asserts the
 * registry covers exactly [TTSProviderSetting.Types] in both directions (no missing provider, no orphan).
 */
internal val TTS_PROVIDER_REGISTRY: Map<KClass<out TTSProviderSetting>, TTSProvider<out TTSProviderSetting>> =
    linkedMapOf(
        TTSProviderSetting.OpenAI::class to OpenAITTSProvider(),
        TTSProviderSetting.Gemini::class to GeminiTTSProvider(),
        TTSProviderSetting.SystemTTS::class to SystemTTSProvider(),
        TTSProviderSetting.MiniMax::class to MiniMaxTTSProvider(),
        TTSProviderSetting.Qwen::class to QwenTTSProvider(),
        TTSProviderSetting.Groq::class to GroqTTSProvider(),
        TTSProviderSetting.XAI::class to XAITTSProvider(),
        TTSProviderSetting.MiMo::class to MiMoTTSProvider(),
    )

class TTSManager(private val context: Context) {

    fun generateSpeech(
        providerSetting: TTSProviderSetting,
        request: TTSRequest
    ): Flow<AudioChunk> {
        // The registry is keyed by the EXACT subtype, so the resolved provider's T is providerSetting's
        // own type — the cast is sound by construction (guarded by TTSProviderRegistryTest). A missing
        // entry is a programming error (a subtype added without a provider), surfaced loudly.
        @Suppress("UNCHECKED_CAST")
        val provider = TTS_PROVIDER_REGISTRY[providerSetting::class] as? TTSProvider<TTSProviderSetting>
            ?: error("No TTS provider registered for ${providerSetting::class.simpleName}")
        return provider.generateSpeech(context, providerSetting, request)
    }
}
