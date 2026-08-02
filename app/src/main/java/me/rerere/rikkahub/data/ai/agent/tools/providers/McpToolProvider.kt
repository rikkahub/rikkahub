package me.rerere.rikkahub.data.ai.agent.tools.providers

import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.mcp.McpToolExecutor
import me.rerere.rikkahub.data.ai.agent.permission.DescribedTool
import me.rerere.rikkahub.data.ai.agent.permission.McpServerPolicyContext
import me.rerere.rikkahub.data.ai.agent.permission.ToolDescriptorRegistry

class McpInvalidServerNameException(
    val invalidNames: List<String>,
) : IllegalStateException("Invalid MCP server names: ${invalidNames.joinToString(", ")}")

class McpToolProvider(
    private val mcpToolExecutor: McpToolExecutor,
) : ToolProvider {
    override val order: Int = ToolProviderOrder.MCP

    override fun isEnabled(ctx: ToolResolveContext): Boolean = true

    override suspend fun provide(ctx: ToolResolveContext): List<Tool> {
        return provideWithDescriptors(ctx).map(DescribedTool::tool)
    }

    override suspend fun provideWithDescriptors(ctx: ToolResolveContext): List<DescribedTool> {
        val allTools = mcpToolExecutor.getAllAvailableTools(ctx.settings, ctx.assistant)
        if (allTools.isEmpty()) return emptyList()
        val frozenServers = ctx.settings.mcpServers.associateBy { it.id }

        return allTools.mapNotNull { (serverId, serverName, tool) ->
            val frozenServer = frozenServers[serverId] ?: return@mapNotNull null
            val exposedName = exposedToolName(serverId.toString(), serverName, tool.name)
            val exposedTool = Tool(
                name = exposedName,
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = { tool.needsApproval },
                execute = {
                    mcpToolExecutor.callTool(ctx.assistant, frozenServer, tool.name, it.jsonObject)
                },
            )
            DescribedTool(
                exposedTool,
                ToolDescriptorRegistry.descriptorFor(exposedTool),
                McpServerPolicyContext(serverId.toString(), serverName, tool.needsApproval),
            )
        }
    }

    /** MCP display names are untrusted; function names must remain portable across providers (<= 64 ASCII chars). */
    private fun exposedToolName(serverId: String, serverName: String, toolName: String): String {
        val serverLabel = functionNamePart(serverName, "server", 8)
        val toolLabel = "${functionNamePart(toolName, "tool", 8)}_${toolName.digestPrefix()}"
        val serverIdentity = serverId.filter(Char::isLetterOrDigit).lowercase().take(32)
        return "mcp__${serverLabel}_${serverIdentity}_${toolLabel}"
    }

    private fun functionNamePart(value: String, fallback: String, maxLength: Int): String =
        value.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '_' }.joinToString("")
            .trim('_')
            .take(maxLength)
            .ifBlank { fallback }

    private fun String.digestPrefix(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(8)
}
