package me.rerere.rikkahub.web.routes

import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.ai.runtime.mcp.McpCommonOptions
import me.rerere.ai.runtime.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.common.json.JsonInstant
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRoutesRedactionTest {

    private val providers = listOf(
        ProviderSetting.OpenAI(
            name = "OpenAI",
            apiKey = "sk-secret",
            baseUrl = "https://openai.example/v1",
        ),
        ProviderSetting.Claude(
            name = "Claude",
            apiKey = "ck-secret",
            oauthToken = "oauth-secret",
            authType = ClaudeAuthType.OAuth,
            baseUrl = "https://claude.example/v1",
        ),
        ProviderSetting.Google(
            name = "Google",
            apiKey = "g-secret",
            privateKey = "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----",
            vertexAI = true,
            useServiceAccount = true,
            baseUrl = "https://google.example/v1beta",
        ),
    )

    @Test
    fun `redacted settings stream contains no provider secrets`() {
        val json = JsonInstant.encodeToString(providers.map { it.redactSecrets() })

        assertFalse("apiKey of OpenAI leaked", json.contains("sk-secret"))
        assertFalse("apiKey of Claude leaked", json.contains("ck-secret"))
        assertFalse("oauthToken of Claude leaked", json.contains("oauth-secret"))
        assertFalse("apiKey of Google leaked", json.contains("g-secret"))
        assertFalse("privateKey of Google leaked", json.contains("BEGIN PRIVATE KEY"))
    }

    @Test
    fun `redaction preserves non-secret fields the web UI consumes`() {
        val json = JsonInstant.encodeToString(providers.map { it.redactSecrets() })

        assertTrue("provider name dropped", json.contains("OpenAI"))
        assertTrue("provider name dropped", json.contains("Claude"))
        assertTrue("provider name dropped", json.contains("Google"))
        assertTrue("baseUrl dropped", json.contains("https://openai.example/v1"))
        assertTrue("baseUrl dropped", json.contains("https://claude.example/v1"))
        assertTrue("baseUrl dropped", json.contains("https://google.example/v1beta"))
    }

    // The SSE stream serializes the WHOLE Settings object, not just providers.
    // Every secret-bearing field reachable from Settings must be stripped before
    // it reaches an unauthenticated LAN client.
    private val settingsWithEverySecret = Settings(
        providers = providers,
        searchServices = listOf(
            SearchServiceOptions.TavilyOptions(apiKey = "search-apikey-secret"),
            SearchServiceOptions.SearXNGOptions(
                url = "https://searx.example",
                username = "search-user-secret",
                password = "search-pass-secret",
            ),
        ),
        ttsProviders = listOf(
            TTSProviderSetting.OpenAI(apiKey = "tts-apikey-secret"),
        ),
        asrProviders = listOf(
            ASRProviderSetting.OpenAIRealtime(apiKey = "asr-apikey-secret"),
            ASRProviderSetting.AnthropicVoice(oauthToken = "asr-oauth-secret"),
        ),
        webDavConfig = WebDavConfig(
            url = "https://dav.example",
            username = "webdav-user-secret",
            password = "webdav-pass-secret",
        ),
        s3Config = S3Config(
            endpoint = "https://s3.example",
            accessKeyId = "s3-access-secret",
            secretAccessKey = "s3-secret-secret",
        ),
        mcpServers = listOf(
            McpServerConfig.StreamableHTTPServer(
                url = "https://mcp.example",
                commonOptions = McpCommonOptions(
                    name = "mcp",
                    headers = listOf("Authorization" to "Bearer mcp-token-secret"),
                ),
            ),
        ),
        webServerAccessPassword = "webserver-pass-secret",
    )

    @Test
    fun `sanitized settings stream contains no non-provider secrets`() {
        val json = JsonInstant.encodeToString(settingsWithEverySecret.sanitizeForWeb())

        assertFalse("search apiKey leaked", json.contains("search-apikey-secret"))
        assertFalse("search username leaked", json.contains("search-user-secret"))
        assertFalse("search password leaked", json.contains("search-pass-secret"))
        assertFalse("tts apiKey leaked", json.contains("tts-apikey-secret"))
        assertFalse("asr apiKey leaked", json.contains("asr-apikey-secret"))
        assertFalse("asr oauthToken leaked", json.contains("asr-oauth-secret"))
        assertFalse("webdav username leaked", json.contains("webdav-user-secret"))
        assertFalse("webdav password leaked", json.contains("webdav-pass-secret"))
        assertFalse("s3 accessKeyId leaked", json.contains("s3-access-secret"))
        assertFalse("s3 secretAccessKey leaked", json.contains("s3-secret-secret"))
        assertFalse("mcp header token leaked", json.contains("mcp-token-secret"))
        assertFalse("web server access password leaked", json.contains("webserver-pass-secret"))
    }

    @Test
    fun `sanitized settings stream still strips provider secrets`() {
        val json = JsonInstant.encodeToString(settingsWithEverySecret.sanitizeForWeb())

        assertFalse("provider apiKey leaked", json.contains("sk-secret"))
        assertFalse("provider oauthToken leaked", json.contains("oauth-secret"))
        assertFalse("provider privateKey leaked", json.contains("BEGIN PRIVATE KEY"))
    }

    @Test
    fun `sanitized settings preserves non-secret fields the web UI consumes`() {
        val json = JsonInstant.encodeToString(settingsWithEverySecret.sanitizeForWeb())

        assertTrue("search url dropped", json.contains("https://searx.example"))
        assertTrue("webdav url dropped", json.contains("https://dav.example"))
        assertTrue("s3 endpoint dropped", json.contains("https://s3.example"))
        assertTrue("mcp url dropped", json.contains("https://mcp.example"))
        assertTrue("mcp header name dropped", json.contains("Authorization"))
    }
}
