package me.rerere.rikkahub.data.ai.mcp

sealed class McpStatus {
    object Idle : McpStatus()
    object Connecting : McpStatus()
    object Connected : McpStatus()
    class Error(val message: String) : McpStatus()
}
