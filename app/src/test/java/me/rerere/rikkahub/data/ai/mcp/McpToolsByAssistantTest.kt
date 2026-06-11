package me.rerere.rikkahub.data.ai.mcp

import me.rerere.ai.core.InputSchema
import me.rerere.ai.runtime.mcp.McpCommonOptions
import me.rerere.ai.runtime.mcp.McpServerConfig
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.ai.runtime.mcp.selectMcpToolsForAssistant
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression test for the MCP-tools-by-target-assistant fix (issue #201, root cause C3).
 *
 * BEFORE the fix, `McpManager.getAllAvailableTools()` took NO assistant argument and selected
 * servers against `settings.getCurrentAssistant()` — the GLOBAL current assistant. A subagent
 * runs as a *different* assistant than the one selected in the UI, so it would have received the
 * PARENT's MCP servers, not its own.
 *
 * The fix passes the target assistant in and selects against ITS allowlist. The load-bearing
 * selection is extracted into the pure [selectMcpToolsForAssistant] (McpManager is otherwise
 * SettingsStore/Android-coupled and not unit-testable directly). This test pins that a server's
 * tools are included iff the server is enabled AND its id is in the PASSED assistant's
 * `mcpServers` — proving selection keys off the ARGUMENT, not a global.
 *
 * FAIL-BEFORE rationale: the pre-fix code path had no parameter to key off and read the global
 * current assistant; an equivalent "select for assistant B" could not even be expressed. With the
 * argument honored, selecting for B returns B's servers and selecting for A returns A's — which is
 * exactly what these assertions require.
 */
class McpToolsByAssistantTest {

    private fun tool(name: String, enable: Boolean = true) =
        McpTool(name = name, enable = enable, inputSchema = InputSchema.Obj(properties = kotlinx.serialization.json.JsonObject(emptyMap())))

    private fun server(id: Uuid, enable: Boolean, vararg tools: McpTool) =
        McpServerConfig.SseTransportServer(
            id = id,
            commonOptions = McpCommonOptions(enable = enable, name = id.toString(), tools = tools.toList()),
            url = "http://localhost/$id",
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
        val assistantA = Assistant(name = "A", mcpServers = setOf(serverA))
        val assistantB = Assistant(name = "B", mcpServers = setOf(serverB))

        val forA = selectMcpToolsForAssistant(allServers, assistantA.toAssistantConfig())
        val forB = selectMcpToolsForAssistant(allServers, assistantB.toAssistantConfig())

        // A gets only A's server's tools.
        assertEquals(listOf(serverA), forA.map { it.first })
        assertEquals(listOf("a_tool"), forA.map { it.second.name })

        // B (a different assistant, e.g. a subagent) gets only B's — NOT A's.
        assertEquals(listOf(serverB), forB.map { it.first })
        assertEquals(listOf("b_tool"), forB.map { it.second.name })
        assertTrue("subagent must not inherit the parent's MCP server", forB.none { it.first == serverA })
    }

    @Test
    fun `a disabled server contributes nothing even when allowlisted`() {
        val assistant = Assistant(name = "X", mcpServers = setOf(serverA, serverDisabled))
        val tools = selectMcpToolsForAssistant(allServers, assistant.toAssistantConfig())

        assertEquals(listOf(serverA), tools.map { it.first })
        assertTrue(tools.none { it.first == serverDisabled })
    }

    @Test
    fun `a disabled tool within an enabled server is excluded`() {
        val srv = Uuid.random()
        val servers = listOf(server(srv, enable = true, tool("on", enable = true), tool("off", enable = false)))
        val assistant = Assistant(name = "X", mcpServers = setOf(srv))

        val tools = selectMcpToolsForAssistant(servers, assistant.toAssistantConfig())
        assertEquals(listOf("on"), tools.map { it.second.name })
    }

    @Test
    fun `an empty allowlist selects nothing`() {
        val assistant = Assistant(name = "empty", mcpServers = emptySet())
        assertTrue(selectMcpToolsForAssistant(allServers, assistant.toAssistantConfig()).isEmpty())
    }
}
