package me.rerere.rikkahub.web.routes

import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting

// The settings SSE stream serializes the whole Settings object to any web
// client. Every credential persisted as an ordinary serializable field would
// otherwise be emitted to an unauthenticated LAN client on the first update
// event. Strip them here, fail-closed: each secret-bearing sealed type is
// matched exhaustively (no `else`), so adding a new variant with a credential
// breaks the build until it is redacted.
internal fun Settings.sanitizeForWeb(): Settings = copy(
    providers = providers.map { it.redactSecrets() },
    searchServices = searchServices.map { it.redactSecrets() },
    ttsProviders = ttsProviders.map { it.redactSecrets() },
    asrProviders = asrProviders.map { it.redactSecrets() },
    webDavConfig = webDavConfig.redactSecrets(),
    s3Config = s3Config.redactSecrets(),
    mcpServers = mcpServers.map { it.redactSecrets() },
    webServerAccessPassword = "",
)

internal fun ProviderSetting.redactSecrets(): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(apiKey = "")
    is ProviderSetting.Google -> copy(apiKey = "", privateKey = "")
    is ProviderSetting.Claude -> copy(apiKey = "", oauthToken = "")
}

internal fun SearchServiceOptions.redactSecrets(): SearchServiceOptions = when (this) {
    is SearchServiceOptions.BingLocalOptions -> this
    is SearchServiceOptions.RikkaHubOptions -> copy(apiKey = "")
    is SearchServiceOptions.ZhipuOptions -> copy(apiKey = "")
    is SearchServiceOptions.TavilyOptions -> copy(apiKey = "")
    is SearchServiceOptions.ExaOptions -> copy(apiKey = "")
    is SearchServiceOptions.SearXNGOptions -> copy(username = "", password = "")
    is SearchServiceOptions.LinkUpOptions -> copy(apiKey = "")
    is SearchServiceOptions.BraveOptions -> copy(apiKey = "")
    is SearchServiceOptions.MetasoOptions -> copy(apiKey = "")
    is SearchServiceOptions.OllamaOptions -> copy(apiKey = "")
    is SearchServiceOptions.PerplexityOptions -> copy(apiKey = "")
    is SearchServiceOptions.FirecrawlOptions -> copy(apiKey = "")
    is SearchServiceOptions.JinaOptions -> copy(apiKey = "")
    is SearchServiceOptions.BochaOptions -> copy(apiKey = "")
    is SearchServiceOptions.GrokOptions -> copy(apiKey = "")
    is SearchServiceOptions.TinyfishOptions -> copy(apiKey = "")
    is SearchServiceOptions.CustomJsOptions -> this
}

internal fun TTSProviderSetting.redactSecrets(): TTSProviderSetting = when (this) {
    is TTSProviderSetting.OpenAI -> copy(apiKey = "")
    is TTSProviderSetting.Gemini -> copy(apiKey = "")
    is TTSProviderSetting.SystemTTS -> this
    is TTSProviderSetting.MiniMax -> copy(apiKey = "")
    is TTSProviderSetting.Qwen -> copy(apiKey = "")
    is TTSProviderSetting.Groq -> copy(apiKey = "")
    is TTSProviderSetting.XAI -> copy(apiKey = "")
    is TTSProviderSetting.MiMo -> copy(apiKey = "")
}

internal fun ASRProviderSetting.redactSecrets(): ASRProviderSetting = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> copy(apiKey = "")
    is ASRProviderSetting.DashScope -> copy(apiKey = "")
    is ASRProviderSetting.Volcengine -> copy(apiKey = "")
    is ASRProviderSetting.AnthropicVoice -> copy(oauthToken = "")
}

internal fun WebDavConfig.redactSecrets(): WebDavConfig = copy(username = "", password = "")

internal fun S3Config.redactSecrets(): S3Config = copy(accessKeyId = "", secretAccessKey = "")

// MCP custom headers routinely carry bearer/authorization tokens; the URL and
// header names are not secret, but every header value is treated as one.
internal fun me.rerere.rikkahub.data.ai.mcp.McpServerConfig.redactSecrets(): me.rerere.rikkahub.data.ai.mcp.McpServerConfig =
    clone(
        commonOptions = commonOptions.copy(
            headers = commonOptions.headers.map { (name, _) -> name to "" }
        )
    )
