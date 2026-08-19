package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIRequestAuthenticatorTest {
    @Test
    fun `API key auth adds bearer token`() = runBlocking {
        val setting = ProviderSetting.OpenAI(apiKey = "sk-test")
        val request = OpenAIRequestAuthenticator(KeyRoulette.default())
            .authenticate(
                Request.Builder().url("https://api.openai.com/v1/responses"),
                setting,
            )
            .build()

        assertEquals("Bearer sk-test", request.header("Authorization"))
    }

    @Test
    fun `subscription auth uses fresh token and account headers`() = runBlocking {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            codexCredentials = OpenAICodexCredentials(
                accessToken = "stale-token",
                refreshToken = "refresh-token",
                accountId = "account-old",
            ),
        )
        val provider = OpenAICodexTokenProvider {
            OpenAICodexCredentials(
                accessToken = "fresh-token",
                refreshToken = "refresh-token-2",
                accountId = "account-new",
            )
        }
        val request = OpenAIRequestAuthenticator(KeyRoulette.default(), provider)
            .authenticate(
                Request.Builder()
                    .url("https://chatgpt.com/backend-api/codex/responses")
                    .header("Authorization", "Bearer custom-token"),
                setting,
            )
            .build()

        assertEquals("Bearer fresh-token", request.header("Authorization"))
        assertEquals("account-new", request.header("ChatGPT-Account-Id"))
        assertEquals("rikkahub", request.header("originator"))
    }
}
