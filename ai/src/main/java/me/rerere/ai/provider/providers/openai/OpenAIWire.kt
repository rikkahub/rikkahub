package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OpenAIMode
import me.rerere.ai.provider.ProviderSetting
import okhttp3.Request

/**
 * Shared OpenAI-wire helpers that branch Standard vs Azure URL/auth in ONE place, so the per-call
 * request builders (OpenAIProvider, ChatCompletionsAPI) never re-derive the Azure rules. The Standard
 * branch is byte-identical to the legacy inline construction — Azure is purely additive.
 *
 * Azure OpenAI differs from the OpenAI-compatible wire in three ways: the model is addressed by its
 * DEPLOYMENT name in the URL path (not by id in the body), auth is the `api-key` header (not a Bearer
 * token), and every data-plane URL carries an `api-version` query param.
 */
internal val ProviderSetting.OpenAI.isAzure: Boolean get() = mode == OpenAIMode.Azure

/**
 * Chat-completions URL for [modelId]. Standard: `{baseUrl}{chatCompletionsPath}` (proxies remap the
 * path). Azure: the deployment-scoped data-plane URL, where the deployment IS the model's modelId.
 */
internal fun ProviderSetting.OpenAI.chatCompletionsUrl(modelId: String): String =
    if (isAzure) azureDeploymentUrl(modelId, "chat/completions") else "$baseUrl$chatCompletionsPath"

/**
 * Azure data-plane URL for [endpoint] (e.g. "chat/completions", "embeddings", "images/generations")
 * under [deployment]: `{root}/openai/deployments/{deployment}/{endpoint}?api-version={azureApiVersion}`.
 * Azure deployment names are restricted to letters/digits/`-`/`_`, so no path encoding is needed.
 */
internal fun ProviderSetting.OpenAI.azureDeploymentUrl(deployment: String, endpoint: String): String {
    val root = baseUrl.trimEnd('/')
    return "$root/openai/deployments/$deployment/$endpoint?api-version=$azureApiVersion"
}

/**
 * Attach the provider's auth header: Azure uses `api-key: <key>`, every other mode uses
 * `Authorization: Bearer <key>`. The Standard output is byte-identical to the legacy inline header.
 */
internal fun Request.Builder.applyOpenAIAuth(setting: ProviderSetting.OpenAI, key: String): Request.Builder =
    if (setting.isAzure) addHeader("api-key", key) else addHeader("Authorization", "Bearer $key")
