package me.rerere.rikkahub.ui.hooks

import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

// Regression guard for the DisposableEffect key in rememberCustomAsrState: the
// AnthropicVoice fallback must be derived from ONLY the first OAuth-configured
// Claude provider's token, so editing any other chat provider does not change the
// effect key (and therefore does not tear down an in-progress ASR recording).
class ASRFallbackTokenTest {

    @Test
    fun `returns first oauth claude token`() {
        val settings = Settings(
            providers = listOf(
                ProviderSetting.Claude(
                    name = "Claude OAuth",
                    authType = ClaudeAuthType.OAuth,
                    oauthToken = "tok-1",
                ),
                ProviderSetting.Claude(
                    name = "Claude OAuth 2",
                    authType = ClaudeAuthType.OAuth,
                    oauthToken = "tok-2",
                ),
            )
        )
        assertEquals("tok-1", resolveClaudeOAuthFallback(settings))
    }

    @Test
    fun `ignores api-key claude and non-claude providers`() {
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(name = "OpenAI", apiKey = "sk-x"),
                ProviderSetting.Claude(
                    name = "Claude ApiKey",
                    authType = ClaudeAuthType.ApiKey,
                    apiKey = "ck-x",
                    oauthToken = "tok-apikey",
                ),
                ProviderSetting.Claude(
                    name = "Claude OAuth blank",
                    authType = ClaudeAuthType.OAuth,
                    oauthToken = "",
                ),
                ProviderSetting.Claude(
                    name = "Claude OAuth real",
                    authType = ClaudeAuthType.OAuth,
                    oauthToken = "tok-real",
                ),
            )
        )
        assertEquals("tok-real", resolveClaudeOAuthFallback(settings))
    }

    @Test
    fun `returns empty when no oauth claude configured`() {
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(name = "OpenAI", apiKey = "sk-x"),
                ProviderSetting.Claude(
                    name = "Claude ApiKey",
                    authType = ClaudeAuthType.ApiKey,
                    apiKey = "ck-x",
                ),
            )
        )
        assertEquals("", resolveClaudeOAuthFallback(settings))

        // The key is stable across edits to an unrelated provider's secret: only the
        // OAuth Claude token feeds the result, so flipping OpenAI's apiKey is a no-op.
        val edited = settings.copy(
            providers = listOf(
                ProviderSetting.OpenAI(name = "OpenAI", apiKey = "sk-y"),
                ProviderSetting.Claude(
                    name = "Claude ApiKey",
                    authType = ClaudeAuthType.ApiKey,
                    apiKey = "ck-x",
                ),
            )
        )
        assertEquals(
            resolveClaudeOAuthFallback(settings),
            resolveClaudeOAuthFallback(edited)
        )
    }
}
