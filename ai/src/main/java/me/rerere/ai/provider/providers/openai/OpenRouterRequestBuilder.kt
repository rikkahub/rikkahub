package me.rerere.ai.provider.providers.openai

/**
 * OpenRouter-specific request-building helpers, isolated from [ChatCompletionsAPI] so the
 * shared OpenAI-compatible path does not bloat. Everything here is only used when the
 * provider host is `openrouter.ai`.
 */

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
