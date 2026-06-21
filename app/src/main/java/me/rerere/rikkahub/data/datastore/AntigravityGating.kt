package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.antigravityImageModels
import me.rerere.search.SearchServiceOptions

/** The configured, usable Antigravity Google provider (enabled + a refresh token), if any. */
fun Settings.antigravityProvider(): ProviderSetting.Google? =
    providers.filterIsInstance<ProviderSetting.Google>()
        .firstOrNull { it.enabled && it.antigravity && it.antigravityRefreshToken.isNotBlank() }

/** True when an Antigravity Google provider is configured — gates the Google Search + image surfaces. */
fun Settings.hasAntigravity(): Boolean = antigravityProvider() != null

/** The live Antigravity refresh token to drive Google Search, or null when not configured. */
fun Settings.antigravityRefreshToken(): String? = antigravityProvider()?.antigravityRefreshToken

/**
 * [options] with the live managed credential injected for the engine that needs one (the credential is
 * @Transient, never persisted — it must be filled from the current provider at use time): the Gagy
 * refresh token for Google Search, the ChatGPT access token for Codex Search; unchanged for other
 * engines. Use this everywhere a search service is about to run (agent tool AND the settings test button).
 */
fun Settings.resolveSearchOptions(options: SearchServiceOptions): SearchServiceOptions =
    when (options) {
        is SearchServiceOptions.GoogleSearchOptions ->
            options.copy(refreshToken = antigravityRefreshToken().orEmpty())

        is SearchServiceOptions.CodexSearchOptions ->
            options.copy(accessToken = chatGptAccessToken().orEmpty())

        else -> options
    }

/**
 * The provider list with Gagy image-gen models merged into the configured Gagy provider,
 * so they surface in the image-gen picker (and resolve at generate time) without the user enabling them
 * manually. Returns the providers unchanged when Gagy isn't configured — so the models stay hidden.
 */
fun Settings.withAntigravityImageModels(): List<ProviderSetting> {
    // Augment exactly ONE provider (the same one [antigravityProvider] / the token resolution use), so
    // the fixed-id image model can't appear twice — two Gagy providers would otherwise collide on
    // Model.id (LazyColumn key + findModelById).
    val targetId = antigravityProvider()?.id ?: return providers
    return providers.map { provider ->
        if (provider.id == targetId && provider is ProviderSetting.Google) {
            val present = provider.models.mapTo(HashSet()) { it.modelId }
            val extra = antigravityImageModels().filter { it.modelId !in present }
            if (extra.isEmpty()) provider else provider.copy(models = provider.models + extra)
        } else {
            provider
        }
    }
}
