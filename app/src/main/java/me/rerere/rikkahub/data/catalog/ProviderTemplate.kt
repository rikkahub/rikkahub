package me.rerere.rikkahub.data.catalog

import me.rerere.ai.provider.ProviderSetting

/**
 * Which local wire adapter ([ProviderSetting] subtype) drives a catalog provider. rikkahub owns the
 * adapters; the catalog only picks among the ones that already exist (clean-architecture: data
 * doesn't define runnable behavior — see the catalog design notes).
 */
enum class ProviderWire { OPENAI, GOOGLE, ANTHROPIC }

/**
 * A curated, locally-owned provider template the user can add from the catalog. The PROVIDER list is
 * curated here (rikkahub knows it can drive these via an API key); the MODEL list for each comes from
 * models.dev at runtime ([modelsDevId]), so models stay fresh without an app release.
 *
 * Adding a template mints a fresh, user-owned (deletable) [ProviderSetting] via [instantiate]; it is
 * NOT a built-in and is independent of the [me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS] seed.
 */
data class ProviderTemplate(
    /** models.dev provider id used to fetch this provider's model catalog. Blank = not on models.dev
     *  (models come from the provider's own /models API + the registry/models.dev gap-fill). */
    val modelsDevId: String,
    val displayName: String,
    val wire: ProviderWire,
    /** OpenAI-compatible base URL. Ignored for GOOGLE/ANTHROPIC (their subtype default is used). */
    val baseUrl: String? = null,
    val docUrl: String? = null,
) {
    /** Mint a fresh user-owned provider from this template (random id, empty key, enabled, deletable). */
    fun instantiate(): ProviderSetting = when (wire) {
        ProviderWire.ANTHROPIC -> ProviderSetting.Claude(
            name = displayName,
            baseUrl = baseUrl ?: ProviderSetting.Claude().baseUrl,
        )

        ProviderWire.GOOGLE -> ProviderSetting.Google(
            name = displayName,
        )

        ProviderWire.OPENAI -> ProviderSetting.OpenAI(
            name = displayName,
            baseUrl = baseUrl ?: ProviderSetting.OpenAI().baseUrl,
        )
    }
}
