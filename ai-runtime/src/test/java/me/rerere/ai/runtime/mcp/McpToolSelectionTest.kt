package me.rerere.ai.runtime.mcp

import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.runtime.contract.AssistantConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Direct unit tests for the moved pure selector [selectMcpToolsForAssistant] (issue #243 slice 6).
 *
 * The selector was moved verbatim from `app/.../McpManager.kt` into `:ai-runtime`, converting its
 * signature from the app `Assistant` to the neutral [AssistantConfig] (it only ever read
 * `assistant.mcpServers`, a `Set<Uuid>` present on both types). These assertions pin the load-bearing
 * selection rule unchanged: a server's tools are included iff the server is enabled AND its id is in
 * the PASSED assistant's allowlist (NOT a global current assistant) — so a subagent never inherits
 * the parent's MCP servers.
 */
class McpToolSelectionTest {

    private fun tool(name: String, enable: Boolean = true) =
        McpTool(name = name, enable = enable, inputSchema = InputSchema.Obj(properties = JsonObject(emptyMap())))

    private fun server(id: Uuid, enable: Boolean, vararg tools: McpTool) =
        McpServerConfig.SseTransportServer(
            id = id,
            commonOptions = McpCommonOptions(enable = enable, name = id.toString(), tools = tools.toList()),
            url = "http://localhost/$id",
        )

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

    private val serverA = Uuid.random()
    private val serverB = Uuid.random()
    private val serverDisabled = Uuid.random()

    private val allServers = listOf(
        server(serverA, enable = true, tool("a_tool")),
        server(serverB, enable = true, tool("b_tool")),
        server(serverDisabled, enable = false, tool("disabled_tool")),
    )

    @Test
    fun `selection follows the passed assistant, not any global current assistant`() {
        val forA = selectMcpToolsForAssistant(allServers, assistant(setOf(serverA)))
        val forB = selectMcpToolsForAssistant(allServers, assistant(setOf(serverB)))

        assertEquals(listOf(serverA), forA.map { it.first })
        assertEquals(listOf("a_tool"), forA.map { it.second.name })

        assertEquals(listOf(serverB), forB.map { it.first })
        assertEquals(listOf("b_tool"), forB.map { it.second.name })
        assertTrue("subagent must not inherit the parent's MCP server", forB.none { it.first == serverA })
    }

    @Test
    fun `a disabled server contributes nothing even when allowlisted`() {
        val tools = selectMcpToolsForAssistant(allServers, assistant(setOf(serverA, serverDisabled)))

        assertEquals(listOf(serverA), tools.map { it.first })
        assertTrue(tools.none { it.first == serverDisabled })
    }

    @Test
    fun `a disabled tool within an enabled server is excluded`() {
        val srv = Uuid.random()
        val servers = listOf(server(srv, enable = true, tool("on", enable = true), tool("off", enable = false)))

        val tools = selectMcpToolsForAssistant(servers, assistant(setOf(srv)))
        assertEquals(listOf("on"), tools.map { it.second.name })
    }

    @Test
    fun `an empty allowlist selects nothing`() {
        assertTrue(selectMcpToolsForAssistant(allServers, assistant(emptySet())).isEmpty())
    }
}
