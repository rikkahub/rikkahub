package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/** Resolves and invokes MCP tools in the Assistant scope captured for a chat session. */
interface McpToolExecutor {
    fun getAllAvailableTools(settings: Settings, assistant: Assistant): List<Triple<Uuid, String, McpTool>>

    suspend fun callTool(
        assistant: Assistant,
        server: McpServerConfig,
        toolName: String,
        args: JsonObject,
    ): List<UIMessagePart>
}
