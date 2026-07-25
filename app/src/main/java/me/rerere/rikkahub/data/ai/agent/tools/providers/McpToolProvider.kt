package me.rerere.rikkahub.data.ai.agent.tools.providers

import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.mcp.McpManager

class McpInvalidServerNameException(
    val invalidNames: List<String>,
) : IllegalStateException("Invalid MCP server names: ${invalidNames.joinToString(", ")}")

class McpToolProvider(
    private val mcpManager: McpManager,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.MCP

    override fun isEnabled(ctx: ToolResolveContext): Boolean = true

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> {
        val allTools = mcpManager.getAllAvailableTools()
        if (allTools.isEmpty()) return emptyList()

        val invalidNames = allTools
            .map { it.second }
            .distinct()
            .filter { name ->
                name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            }
        if (invalidNames.isNotEmpty()) {
            throw McpInvalidServerNameException(invalidNames)
        }

        return allTools.map { (serverId, serverName, tool) ->
            Tool(
                name = "mcp__${serverName}__${tool.name}",
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = { tool.needsApproval },
                execute = {
                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                },
            )
        }
    }
}
