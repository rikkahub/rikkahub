package me.rerere.ai.provider.providers.openai

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false
        )

        else -> ResponseProviderCapabilities()
    }
}
