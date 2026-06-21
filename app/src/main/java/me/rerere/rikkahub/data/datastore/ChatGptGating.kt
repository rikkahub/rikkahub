package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.codexImageModels

/** The configured, usable ChatGPT (Codex) provider (enabled + an access token), if any. */
fun Settings.chatGptProvider(): ProviderSetting.ChatGPT? =
    providers.filterIsInstance<ProviderSetting.ChatGPT>()
        .firstOrNull { it.enabled && it.accessToken.isNotBlank() }

/** True when a ChatGPT provider is configured — gates the Codex search, image-gen + fetch surfaces. */
fun Settings.hasChatGpt(): Boolean = chatGptProvider() != null

/** The live ChatGPT (Codex) access token to drive search/fetch/image-gen, or null when not configured. */
fun Settings.chatGptAccessToken(): String? = chatGptProvider()?.accessToken

/**
 * The provider list with the Codex image-gen model merged into the configured ChatGPT provider, so it
 * surfaces in the image-gen picker (and resolves at generate time) without the user adding it manually.
 * Returns the providers unchanged when ChatGPT isn't configured — so the model stays hidden.
 */
fun Settings.withChatGptImageModels(): List<ProviderSetting> {
    // Augment exactly ONE provider (the same one chatGptProvider()/the token resolution use), so the
    // fixed-id image model can't appear twice — two ChatGPT providers would otherwise collide on
    // Model.id (LazyColumn key + findModelById).
    val targetId = chatGptProvider()?.id ?: return providers
    return providers.map { provider ->
        if (provider.id == targetId && provider is ProviderSetting.ChatGPT) {
            // Dedup by the stable Model.id, NOT modelId: the image model's modelId is the driver slug
            // ("gpt-5.5"), which also names a normal ChatGPT chat model — deduping by modelId would
            // drop the image model whenever that chat model is already in the provider's list, so the
            // image picker (filtered to ModelType.IMAGE) would show nothing.
            val present = provider.models.mapTo(HashSet()) { it.id }
            val extra = codexImageModels().filter { it.id !in present }
            if (extra.isEmpty()) provider else provider.copy(models = provider.models + extra)
        } else {
            provider
        }
    }
}

/**
 * The provider list with BOTH managed image-gen models merged in (Gagy's Nano-Banana and Codex's
 * gpt-image-2), so the image-gen picker surfaces whichever of the two managed providers is configured.
 * Each helper no-ops when its provider is absent, so this is safe regardless of what's set up.
 */
fun Settings.withManagedImageModels(): List<ProviderSetting> =
    copy(providers = withAntigravityImageModels()).withChatGptImageModels()
