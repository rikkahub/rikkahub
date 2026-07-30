package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.routing.AgentRoutingSnapshot
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolProviderOrder
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.ai.agent.routing.AgentIntent
import me.rerere.rikkahub.data.ai.agent.routing.InputTrust
import me.rerere.rikkahub.data.ai.agent.routing.ToolNameCollisionException
import me.rerere.rikkahub.data.ai.agent.tools.providers.McpToolProvider
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.McpToolExecutor
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `plan mode exposes only the explicit read-only allowlist`() = runBlocking {
        val registry = ToolRegistry(
            listOf(
                FixedProvider(
                    ToolProviderOrder.WORKSPACE,
                    listOf(
                        "workspace_read_file",
                        "workspace_write_file",
                        "workspace_edit_file",
                        "workspace_shell",
                        "calendar_query",
                        "calendar_create",
                        "clipboard_tool",
                        "memory_tool",
                        "eval_javascript",
                        "text_to_speech",
                        "ask_user",
                        "get_screen_time",
                        "recent_chats",
                        "conversation_search",
                        "use_skill",
                        "mcp__demo__unknown_side_effect",
                    )
                )
            )
        )
        val planNames = registry.resolve(ctx(AgentMode.PLAN)).map { it.name }
        assertEquals(
            listOf(
                "workspace_read_file",
                "calendar_query",
                "recent_chats",
                "conversation_search",
                "use_skill",
            ),
            planNames,
        )
        assertTrue(planNames.none(::isPlanModeBlockedTool))

        val agentNames = registry.resolve(ctx(AgentMode.AGENT)).map { it.name }.toSet()
        assertTrue(
            agentNames.containsAll(
                setOf(
                    "workspace_write_file",
                    "workspace_edit_file",
                    "workspace_shell",
                    "calendar_create",
                    "clipboard_tool",
                    "memory_tool",
                    "eval_javascript",
                    "text_to_speech",
                    "ask_user",
                    "get_screen_time",
                    "mcp__demo__unknown_side_effect",
                ),
            ),
        )
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
        assertTrue(chatNames.containsAll(setOf("workspace_write_file", "workspace_edit_file", "workspace_shell")))
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

    @Test
    fun `auto profile discovers the full candidate set through agent compatibility mode`() = runBlocking {
        val modeAwareProvider = object : ToolProvider {
            override val order: Int = ToolProviderOrder.MCP

            override fun isEnabled(ctx: ToolResolveContext): Boolean = ctx.mode == AgentMode.AGENT

            override suspend fun provide(ctx: ToolResolveContext): List<Tool> = listOf(
                Tool(
                    name = "mcp__server__tool",
                    description = "mcp",
                    execute = { listOf(UIMessagePart.Text("ok")) },
                ),
            )
        }
        val profile = ToolRegistry(listOf(modeAwareProvider)).resolveProfile(
            ctx = ctx(AgentMode.PLAN),
            intent = AgentIntent.EXECUTE,
            inputTrust = InputTrust.USER_DIRECT,
            defaultToolTimeoutMillis = 30_000,
        )

        assertEquals(listOf("mcp__server__tool"), profile.resolvedToolNames)
    }

    @Test
    fun `frozen profile ignores unrelated MCP duplicates while new profile fails closed`() = runBlocking {
        val executor = object : McpToolExecutor {
            var availableTools = emptyList<Triple<Uuid, String, McpTool>>()

            override fun getAllAvailableTools(assistant: Assistant) = availableTools

            override suspend fun callTool(
                assistant: Assistant,
                serverId: Uuid,
                toolName: String,
                args: JsonObject,
            ): List<UIMessagePart> = emptyList()
        }
        val registry = ToolRegistry(
            listOf(
                FixedProvider(ToolProviderOrder.LOCAL, listOf("get_time_info")),
                McpToolProvider(executor),
            ),
        )
        val context = ctx(AgentMode.PLAN)
        val original = registry.resolveProfile(
            ctx = context,
            intent = AgentIntent.ANSWER,
            inputTrust = InputTrust.USER_DIRECT,
            defaultToolTimeoutMillis = 30_000,
        )
        val snapshot = AgentRoutingSnapshot.create(
            intent = AgentIntent.ANSWER,
            inputTrust = InputTrust.USER_DIRECT,
            reasonCode = "registry_test",
            resolvedToolNames = original.resolvedToolNames,
            permissionDigest = original.permissionDigest,
            providerIdleTimeoutMillis = 60_000,
            toolTimeoutMillis = 30_000,
            runTimeoutMillis = 600_000,
        )
        val serverId = Uuid.random()
        val duplicate = Triple(serverId, "duplicate", McpTool(name = "inspect"))
        executor.availableTools = listOf(duplicate, duplicate)

        val frozen = registry.resolveFrozenProfile(context, snapshot)
        assertEquals(listOf("get_time_info"), frozen.resolvedToolNames)

        try {
            registry.resolveProfile(
                ctx = context,
                intent = AgentIntent.EXECUTE,
                inputTrust = InputTrust.USER_DIRECT,
                defaultToolTimeoutMillis = 30_000,
            )
            fail("New profiles must reject global MCP duplicates")
        } catch (_: ToolNameCollisionException) {
            // Expected.
        }
    }
}
