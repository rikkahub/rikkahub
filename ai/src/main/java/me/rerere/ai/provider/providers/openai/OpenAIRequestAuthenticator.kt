package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.Request

/** Supplies a fresh ChatGPT subscription token to the provider request layer. */
fun interface OpenAICodexTokenProvider {
    suspend fun getCredentials(providerSetting: ProviderSetting.OpenAI): OpenAICodexCredentials
}

/** Centralizes authentication headers so every OpenAI endpoint uses the selected auth mode. */
internal class OpenAIRequestAuthenticator(
    private val keyRoulette: KeyRoulette,
    private val codexTokenProvider: OpenAICodexTokenProvider? = null,
) {
    suspend fun authenticate(
        builder: Request.Builder,
        providerSetting: ProviderSetting.OpenAI,
    ): Request.Builder {
        return when (providerSetting.authType) {
            OpenAIAuthType.API_KEY -> {
                val key = keyRoulette.next(
                    providerSetting.apiKey,
                    providerSetting.id.toString(),
                )
                builder.header(AUTHORIZATION_HEADER, "Bearer $key")
            }

            OpenAIAuthType.CHATGPT_SUBSCRIPTION -> {
                val credentials = codexTokenProvider?.getCredentials(providerSetting)
                    ?: providerSetting.codexCredentials
                    ?: error("OpenAI Codex is not signed in. Sign in with ChatGPT first.")
                require(credentials.accessToken.isNotBlank()) {
                    "OpenAI Codex access token is empty. Sign in with ChatGPT again."
                }
                require(credentials.accountId.isNotBlank()) {
                    "OpenAI Codex account is missing. Sign in with ChatGPT again."
                }
                builder
                    .header(AUTHORIZATION_HEADER, "Bearer ${credentials.accessToken}")
                    .header(CHATGPT_ACCOUNT_HEADER, credentials.accountId)
                    .header(ORIGINATOR_HEADER, ORIGINATOR_VALUE)
            }
        }
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val CHATGPT_ACCOUNT_HEADER = "ChatGPT-Account-Id"
        const val ORIGINATOR_HEADER = "originator"
        const val ORIGINATOR_VALUE = "rikkahub"
    }
}
