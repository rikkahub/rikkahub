package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolRegistryTest {
    private class FixedProvider(
        override val order: Int,
        private val names: List<String>,
        private val enabled: Boolean = true,
    ) : ToolProvider {
        override fun isEnabled(ctx: ToolResolveContext): Boolean = enabled
        override suspend fun provide(ctx: ToolResolveContext): List<Tool> =
            names.map { name ->
                Tool(
                    name = name,
                    description = name,
                    execute = { listOf(UIMessagePart.Text("ok")) },
                )
            }
    }

    private fun ctx(mode: AgentMode = AgentMode.CHAT): ToolResolveContext {
        val assistant = Assistant(id = Uuid.random(), name = "t")
        return ToolResolveContext(
            settings = Settings(),
            assistant = assistant,
            conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistant.id),
            mode = mode,
        )
    }

    @Test
    fun `providers resolve in order`() = runBlocking {
        val registry = ToolRegistry(
            listOf(
                FixedProvider(ToolProviderOrder.MCP, listOf("mcp__a__t")),
                FixedProvider(ToolProviderOrder.SEARCH, listOf("search_web")),
                FixedProvider(ToolProviderOrder.MEMORY, listOf("memory_tool")),
            )
        )
        val names = registry.resolve(ctx()).map { it.name }
        assertEquals(listOf("memory_tool", "search_web", "mcp__a__t"), names)
    }

    @Test
    fun `plan mode filters write and shell tools`() = runBlocking {
        val registry = ToolRegistry(
            listOf(
                FixedProvider(
                    ToolProviderOrder.WORKSPACE,
                    listOf(
                        "workspace_read_file",
                        "workspace_write_file",
                        "workspace_edit_file",
                        "workspace_shell",
                    )
                )
            )
        )
        val planNames = registry.resolve(ctx(AgentMode.PLAN)).map { it.name }
        assertEquals(listOf("workspace_read_file"), planNames)
        assertTrue(planNames.none { it in PlanModeBlockedTools })

        val agentNames = registry.resolve(ctx(AgentMode.AGENT)).map { it.name }.toSet()
        assertTrue(agentNames.containsAll(PlanModeBlockedTools))
        assertFalse(registry.resolve(ctx(AgentMode.CHAT)).isEmpty())
    }

    @Test
    fun `disabled providers are skipped`() = runBlocking {
        val registry = ToolRegistry(
            listOf(
                FixedProvider(ToolProviderOrder.SEARCH, listOf("search_web"), enabled = false),
                FixedProvider(ToolProviderOrder.LOCAL, listOf("get_time_info"), enabled = true),
            )
        )
        assertEquals(listOf("get_time_info"), registry.resolve(ctx()).map { it.name })
    }

    @Test
    fun `chat mode keeps full workspace tool set including write and shell`() = runBlocking {
        val workspaceTools = listOf(
            "workspace_read_file",
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_shell",
        )
        val registry = ToolRegistry(
            listOf(
                FixedProvider(ToolProviderOrder.MEMORY, listOf("memory_tool")),
                FixedProvider(ToolProviderOrder.SEARCH, listOf("search_web")),
                FixedProvider(ToolProviderOrder.WORKSPACE, workspaceTools),
                FixedProvider(ToolProviderOrder.SUBAGENT, listOf("explore_subagent")),
            )
        )
        val chatNames = registry.resolve(ctx(AgentMode.CHAT)).map { it.name }
        assertEquals(
            listOf(
                "memory_tool",
                "search_web",
                "workspace_read_file",
                "workspace_write_file",
                "workspace_edit_file",
                "workspace_shell",
                "explore_subagent",
            ),
            chatNames,
        )
        assertTrue(chatNames.containsAll(PlanModeBlockedTools))
    }

    @Test
    fun `agent mode same full set as chat for workspace tools`() = runBlocking {
        val registry = ToolRegistry(
            listOf(
                FixedProvider(
                    ToolProviderOrder.WORKSPACE,
                    listOf(
                        "workspace_read_file",
                        "workspace_write_file",
                        "workspace_edit_file",
                        "workspace_shell",
                    ),
                )
            )
        )
        assertEquals(
            registry.resolve(ctx(AgentMode.CHAT)).map { it.name },
            registry.resolve(ctx(AgentMode.AGENT)).map { it.name },
        )
    }
}
