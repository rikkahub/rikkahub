package me.rerere.ai.provider

import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.util.KeyCursorStore
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient

class ProviderManager(
    client: OkHttpClient,
    keyCursorStore: KeyCursorStore? = null
) {
    private val providers = mutableMapOf<String, Provider<*>>()
    private val keyRoulette = KeyRoulette.default(keyCursorStore)

    init {
        registerProvider("openai", OpenAIProvider(client, keyRoulette))
        registerProvider("google", GoogleProvider(client, keyRoulette))
        registerProvider("claude", ClaudeProvider(client, keyRoulette))
    }

    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
        } as Provider<T>
    }
}
