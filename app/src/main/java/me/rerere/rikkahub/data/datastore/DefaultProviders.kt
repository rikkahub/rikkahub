package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * Sentinel id for the "no model selected" state. The default model pointers
 * ([Settings.chatModelId]/[Settings.fastModelId]/[Settings.translateModeId]/[Settings.compressModelId])
 * fall back to this when the user has never picked a model, so [Settings.findModelById] resolves to null
 * and the UI shows the onboarding/pick-a-model state instead of a stale "auto" gateway model.
 */
val UNSET_MODEL_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")

/**
 * The curated providers seeded on first run. The long tail of hardcoded providers has moved to the
 * browseable catalog (the user adds only the ones they pick — see [me.rerere.rikkahub.data.catalog]);
 * only the two flagship wires ship as a starting point, default Anthropic. They are NOT built-in
 * (deletable/renamable) — the seed is a convenience, not a fixture. Models are empty by design: the
 * user picks them from the catalog or the provider's own /models. The previous hosted "RikkaHub auto"
 * gateway is dropped.
 *
 * Seeding is one-time (the [me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV6Migration]
 * writes these once), so a deleted default stays deleted — it is not force-re-added on every read.
 */
val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.Claude(
        id = Uuid.parse("7e1d3a2b-9c4f-4e8a-b6d1-0a1b2c3d4e5f"),
        name = "Anthropic",
        apiKey = "",
        enabled = true,
    ),
    ProviderSetting.OpenAI(
        // Same id as the legacy "OpenAI" built-in so an existing user's configured key/models survive.
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        enabled = true,
    ),
)

/**
 * Ids of providers that used to be hardcoded built-ins but no longer ship as defaults. The one-time V6
 * migration removes a provider with one of these ids ONLY if it is pristine (no credentials of any kind
 * and no models) — a user who configured it keeps it as a normal, user-owned (deletable) provider. The
 * kept OpenAI id (`1eeea727…`) is deliberately absent: it is a current default, not legacy.
 */
val LEGACY_BUILTIN_PROVIDER_IDS: Set<String> = setOf(
    "a8d2d463-e8c0-41f2-b89e-f5eb8e716cce", // RikkaHub (hosted "auto" gateway)
    "6ab18148-c138-4394-a46f-1cd8c8ceaa6d", // Gemini
    "1b1395ed-b702-4aeb-8bc1-b681c4456953", // AiHubMix
    "56a94d29-c88b-41c5-8e09-38a7612d6cf8", // SiliconFlow
    "f099ad5b-ef03-446d-8e78-7e36787f780b", // DeepSeek
    "d5734028-d39b-4d41-9841-fd648d65440e", // OpenRouter
    "386e0f29-8228-4512-affe-8fd8add82d88", // Vercel AI Gateway
    "da020a90-f7b3-4c29-b90e-c511a0630630", // TokenPony
    "f76cae46-069a-4334-ab8e-224e4979e58c", // Alibaba Bailian
    "3dfd6f9b-f9d9-417f-80c1-ff8d77184191", // Volcengine
    "d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3", // Moonshot
    "3bc40dc1-b11a-46fa-863b-6306971223be", // Zhipu AI
    "f4f8870e-82d3-495b-9b64-d58e508b3b2c", // StepFun
    "da93779f-3956-48cc-82ef-67bb482eaaf7", // 302.AI
    "ef5d149b-8e34-404b-818c-6ec242e5c3c5", // Tencent Hunyuan
    "ff3cde7e-0f65-43d7-8fb2-6475c99f5990", // xAI
    "53027b08-1b58-43d5-90ed-29173203e3d8", // AckAI
)
