package me.rerere.rikkahub.data.ai.agent.tools.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.McpToolExecutor
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpToolProviderTest {
    private class RecordingMcpToolExecutor : McpToolExecutor {
        val serverId = Uuid.random()
        var availableTools: List<Triple<Uuid, String, McpTool>>? = null
        var resolvedAssistantId: Uuid? = null
        var calledAssistantId: Uuid? = null
        val calledServerIds = mutableListOf<Uuid>()

        override fun getAllAvailableTools(
            settings: Settings,
            assistant: Assistant,
        ): List<Triple<Uuid, String, McpTool>> {
            resolvedAssistantId = assistant.id
            return availableTools ?: listOf(Triple(serverId, "demo", McpTool(name = "inspect")))
        }

        override suspend fun callTool(
            assistant: Assistant,
            server: McpServerConfig,
            toolName: String,
            args: JsonObject,
        ): List<UIMessagePart> {
            calledAssistantId = assistant.id
            calledServerIds += server.id
            return listOf(UIMessagePart.Text("ok"))
        }
    }

    private fun context(assistant: Assistant, mode: AgentMode = AgentMode.CHAT) = ToolResolveContext(
        settings = Settings(
            mcpServers = assistant.mcpServers.map { serverId ->
                McpServerConfig.StreamableHTTPServer(
                    id = serverId,
                    commonOptions = McpCommonOptions(name = "server"),
                )
            },
        ),
        assistant = assistant,
        conversation = Conversation.ofId(Uuid.random(), assistant.id),
        mode = mode,
    )

    @Test
    fun `MCP resolution and execution retain the conversation assistant`() = runBlocking {
        val executor = RecordingMcpToolExecutor()
        val conversationAssistant = Assistant(id = Uuid.random(), mcpServers = setOf(executor.serverId))
        val switchedAssistant = Assistant(id = Uuid.random())
        val provider = McpToolProvider(executor)
        val tools = provider.provide(context(conversationAssistant))

        tools.single().execute(JsonObject(emptyMap()))

        assertEquals(conversationAssistant.id, executor.resolvedAssistantId)
        assertEquals(conversationAssistant.id, executor.calledAssistantId)
        assertFalse(executor.calledAssistantId == switchedAssistant.id)
    }

    @Test
    fun `PLAN exposes MCP tools after planning`() = runBlocking {
        val provider = McpToolProvider(RecordingMcpToolExecutor())
        val assistant = Assistant(id = Uuid.random())

        assertTrue(provider.isEnabled(context(assistant, AgentMode.PLAN)))
    }

    @Test
    fun `same server and tool names retain distinct server lineages`() = runBlocking {
        val executor = RecordingMcpToolExecutor()
        val firstServer = Uuid.random()
        val secondServer = Uuid.random()
        val assistant = Assistant(id = Uuid.random(), mcpServers = setOf(firstServer, secondServer))
        executor.availableTools = listOf(
            Triple(firstServer, "shared server", McpTool(name = "inspect-file")),
            Triple(secondServer, "shared server", McpTool(name = "inspect-file")),
        )

        val tools = McpToolProvider(executor).provideWithDescriptors(context(assistant))

        assertEquals(2, tools.size)
        assertNotEquals(tools[0].tool.name, tools[1].tool.name)
        assertTrue(tools.all { it.tool.name.length <= 64 && it.tool.name.matches(Regex("[A-Za-z0-9_]+")) })
        assertEquals(firstServer.toString(), tools[0].mcpServer?.serverId)
        assertEquals(secondServer.toString(), tools[1].mcpServer?.serverId)

        tools.forEach { it.tool.execute(JsonObject(emptyMap())) }
        assertEquals(listOf(firstServer, secondServer), executor.calledServerIds)
    }
}
