package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.OpenRouterRouting

/**
 * OpenRouter-specific request-building helpers, isolated from [ChatCompletionsAPI] so the
 * shared OpenAI-compatible path does not bloat. Everything here is only used when the
 * provider host is `openrouter.ai`.
 */

/**
 * Build the OpenRouter `provider` routing object, or null when nothing should be sent
 * (so default load balancing is preserved).
 *
 * [hasToolsOrSchema] forces `require_parameters` so capability-mismatched providers can't
 * be picked and silently drop tools / response_format.
 */
fun buildProviderObject(routing: OpenRouterRouting, hasToolsOrSchema: Boolean): JsonObject? {
    val forceRequire = routing.requireParameters || hasToolsOrSchema
    if (routing.isDefault() && !forceRequire) return null
    return buildJsonObject {
        routing.sort?.let { put("sort", it) }
        if (routing.order.isNotEmpty()) putJsonArray("order") { routing.order.forEach { add(it) } }
        if (routing.only.isNotEmpty()) putJsonArray("only") { routing.only.forEach { add(it) } }
        if (routing.ignore.isNotEmpty()) putJsonArray("ignore") { routing.ignore.forEach { add(it) } }
        // allow_fallbacks is only meaningful alongside order/only
        if ((routing.order.isNotEmpty() || routing.only.isNotEmpty()) && !routing.allowFallbacks) {
            put("allow_fallbacks", false)
        }
        if (forceRequire) put("require_parameters", true)
        routing.dataCollection?.let { put("data_collection", it) }
        if (routing.zdr) put("zdr", true)
        if (routing.quantizations.isNotEmpty()) {
            putJsonArray("quantizations") { routing.quantizations.forEach { add(it) } }
        }
        if (routing.maxPricePrompt != null || routing.maxPriceCompletion != null) {
            putJsonObject("max_price") {
                routing.maxPricePrompt?.let { put("prompt", it) }
                routing.maxPriceCompletion?.let { put("completion", it) }
            }
        }
    }
}

data class ParsedImageDataUri(val mime: String, val base64: String)

private val DATA_URI_REGEX =
    Regex("^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)

/**
 * Parse any image data URI (png/jpeg/webp/...) into its mime and base64 payload.
 * Returns null for non-data-URIs (e.g. http URLs) or malformed input.
 *
 * Replaces the old hardcoded `substringAfter("data:image/png;base64,")`, which silently
 * returned the whole string for non-png mimes and produced unrenderable bytes.
 */
fun parseImageDataUri(url: String): ParsedImageDataUri? {
    val m = DATA_URI_REGEX.matchEntire(url.trim()) ?: return null
    return ParsedImageDataUri(mime = m.groupValues[1], base64 = m.groupValues[2])
}
