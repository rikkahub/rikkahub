package me.rerere.ai.provider

import kotlinx.serialization.Serializable

/**
 * OpenRouter provider-routing preferences. Serialized into the `provider` object of a
 * chat-completions request when the provider host is `openrouter.ai`. All fields are
 * optional; when nothing is set the `provider` object is omitted entirely so OpenRouter's
 * default price-weighted load balancing is preserved.
 *
 * See https://openrouter.ai/docs/guides/routing/provider-selection
 */
@Serializable
data class OpenRouterRouting(
    /** "price" | "throughput" | "latency"; null = OpenRouter default (load balanced). */
    val sort: String? = null,
    /** Provider slugs to try in order (disables load balancing). */
    val order: List<String> = emptyList(),
    /** Whitelist: only route to these provider slugs. */
    val only: List<String> = emptyList(),
    /** Blacklist: never route to these provider slugs. */
    val ignore: List<String> = emptyList(),
    /** When false (with order/only set), do not fall back beyond the listed providers. */
    val allowFallbacks: Boolean = true,
    /** Route only to providers that support every parameter in the request. */
    val requireParameters: Boolean = false,
    /** null = allow; "deny" excludes providers that may store/train on data. */
    val dataCollection: String? = null,
    /** Route only to Zero Data Retention endpoints. */
    val zdr: Boolean = false,
    /** Weight quantization filter, e.g. fp8, fp16, bf16. */
    val quantizations: List<String> = emptyList(),
    /** USD per 1M prompt tokens ceiling (hard filter). */
    val maxPricePrompt: Double? = null,
    /** USD per 1M completion tokens ceiling (hard filter). */
    val maxPriceCompletion: Double? = null,
) {
    /** True when nothing is set → the caller should omit the `provider` object. */
    fun isDefault(): Boolean =
        sort == null && order.isEmpty() && only.isEmpty() && ignore.isEmpty() &&
            allowFallbacks && !requireParameters && dataCollection == null && !zdr &&
            quantizations.isEmpty() && maxPricePrompt == null && maxPriceCompletion == null
}
