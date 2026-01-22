package me.rerere.rikkahub.data.ai.mcp

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()
    data class Error(val message: String) : McpStatus()
}
