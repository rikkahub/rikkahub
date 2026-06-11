package me.rerere.ai.runtime

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.ToolCatalog
import me.rerere.ai.runtime.contract.TurnMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Contract-level guard that MCP-tool selection keys off the TARGET assistant (issue #243 §C3): a
 * subagent does NOT inherit the parent's MCP servers, and selected tool names carry the `mcp__`
 * prefix. Drives a fake [ToolCatalog] mirroring the app `selectMcpToolsForAssistant` semantics so the
 * invariant is pinned at the neutral boundary without an app dependency.
 */
class McpToolSelectionRuntimeTest {

    private fun assistant(mcpServers: Set<Uuid>): AssistantConfig = AssistantConfig(
        id = Uuid.random(),
        chatModelId = null,
        systemPrompt = "",
        streamOutput = true,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        messageTemplate = "{{ message }}",
        regexes = emptyList(),
        reasoningLevel = ReasoningLevel.AUTO,
        allowConversationSystemPrompt = false,
        temperature = null,
        topP = null,
        contextMessageSize = 0,
        maxTokens = null,
        customHeaders = emptyList(),
        customBodies = emptyList(),
        mcpServers = mcpServers,
        localToolIds = emptyList(),
        enabledSkills = emptySet(),
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
        knowledgeBaseId = null,
        description = "",
        spawnable = false,
        subagentMaxSteps = null,
    )

    // server-id -> the mcp tool name that server exposes.
    private inner class McpSelectingCatalog(
        private val serverTools: Map<Uuid, String>,
    ) : ToolCatalog {
        override suspend fun tools(ctx: ToolAssemblyContext): List<Tool> =
            ctx.targetAssistant.mcpServers
                .mapNotNull { serverTools[it] }
                .map { toolName ->
                    Tool(name = "mcp__$toolName", description = "", execute = { emptyList() })
                }
    }

    @Test
    fun selectionKeysOffTargetAssistantNotParent() = runBlocking {
        val serverA = Uuid.random()
        val serverB = Uuid.random()
        val catalog = McpSelectingCatalog(mapOf(serverA to "alpha", serverB to "beta"))

        val parent = assistant(mcpServers = setOf(serverA))
        val sub = assistant(mcpServers = setOf(serverB))

        val subPool = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        )
        val names = subPool.map { it.name }

        assertTrue("subagent selects its OWN server's tool", names.contains("mcp__beta"))
        assertFalse("subagent does NOT inherit parent's server tool", names.contains("mcp__alpha"))

        // Parent turn, by contrast, selects parent's server.
        val parentPool = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = parent,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        )
        assertTrue(parentPool.map { it.name }.contains("mcp__alpha"))
        assertFalse(parentPool.map { it.name }.contains("mcp__beta"))
    }

    @Test
    fun selectedNamesCarryMcpPrefix() = runBlocking {
        val server = Uuid.random()
        val catalog = McpSelectingCatalog(mapOf(server to "search"))
        val asst = assistant(mcpServers = setOf(server))

        val pool = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = asst,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        )

        assertTrue(pool.all { it.name.startsWith("mcp__") })
    }
}
