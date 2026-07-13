package me.rerere.rikkahub.data.sync.cloud

import kotlinx.serialization.Serializable

@Serializable
data class PerryServerConfig(
    val scheme: String = "http",
    val host: String = "",
    val port: Int? = 8787,
    val basePath: String = "",
    val deviceName: String = "",
    /** One-time server bootstrap token used only for device registration. */
    val bootstrapToken: String = "",
) {
    fun isConfigured(): Boolean = host.isNotBlank()

    fun normalizedBaseUrl(): String {
        val hostPart = host.trim().trimEnd('/')
        require(hostPart.isNotBlank()) { "host is empty" }
        val schemePart = scheme.trim().lowercase().ifBlank { "https" }
        val portPart = port?.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        val path = basePath.trim().let { raw ->
            when {
                raw.isBlank() -> ""
                raw.startsWith("/") -> raw.trimEnd('/')
                else -> "/${raw.trimEnd('/')}"
            }
        }
        return "$schemePart://$hostPart$portPart$path"
    }
}
