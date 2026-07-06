package me.rerere.rikkahub.data.event

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** MCP OAuth 授权完成后经 deep link 回传的结果。 */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()
}
