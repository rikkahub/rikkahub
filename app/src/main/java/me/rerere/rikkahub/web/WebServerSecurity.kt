package me.rerere.rikkahub.web

const val WEB_SERVER_LOOPBACK_HOST = "127.0.0.1"
const val WEB_SERVER_ALL_INTERFACES_HOST = "0.0.0.0"

data class WebServerStartupOptions(
    val localhostOnly: Boolean = true,
    val jwtEnabled: Boolean = false,
    val accessPassword: String = "",
)

fun WebServerStartupOptions.validationError(): String? {
    if (localhostOnly) return null
    if (!jwtEnabled) return "LAN access requires JWT authentication to be enabled"
    if (accessPassword.isBlank()) return "LAN access requires a non-empty access password"
    return null
}

/** A rejected LAN configuration must not be retried automatically on every app launch. */
fun WebServerStartupOptions.webServerEnabledAfterValidation(enabled: Boolean): Boolean =
    if (validationError() == null) enabled else false

fun WebServerStartupOptions.host(): String =
    if (localhostOnly) WEB_SERVER_LOOPBACK_HOST else WEB_SERVER_ALL_INTERFACES_HOST
