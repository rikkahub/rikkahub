package me.rerere.rikkahub.data.ai.agent.permission

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.AgentMode
import me.rerere.rikkahub.data.ai.agent.tools.ToolProvider
import me.rerere.rikkahub.data.ai.agent.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.agent.tools.ToolResolveContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ToolDescriptorRegistryTest {
    @Test
    fun `built-ins have explicit stable descriptors`() {
        val descriptors = listOf(
            "search_web",
            "clipboard_tool",
            "workspace_read_file",
            "workspace_write_file",
            "workspace_shell",
            "memory_tool",
            "use_skill",
            "explore_subagent",
        ).map(ToolDescriptorRegistry::descriptorFor)

        assertTrue(descriptors.none { it.capability == ToolCapability.UNKNOWN })
        assertTrue(descriptors.all { it.timeoutMillis != null })
        assertTrue(descriptors.none { it.idempotency == ToolIdempotency.UNKNOWN })
    }

    @Test
    fun `MCP and unknown tools receive conservative descriptors`() {
        val mcp = ToolDescriptorRegistry.descriptorFor("mcp__demo__dangerous")
        val unknown = ToolDescriptorRegistry.descriptorFor("future_tool")

        assertEquals(ToolCapability.MCP, mcp.capability)
        assertEquals(ToolDefaultApproval.ASK, mcp.defaultApproval)
        assertEquals(ToolCapability.UNKNOWN, unknown.capability)
        assertEquals(ToolDefaultApproval.ASK, unknown.defaultApproval)
        assertEquals(ToolRedactionPolicy.REDACT_ALL, unknown.redactionPolicy)
    }

    @Test
    fun `descriptor round-trips through Kotlin serialization`() {
        val descriptor = ToolDescriptorRegistry.descriptorFor("workspace_shell")
        val encoded = Json.encodeToString(ToolDescriptor.serializer(), descriptor)
        val decoded = Json.decodeFromString(ToolDescriptor.serializer(), encoded)

        assertEquals(descriptor, decoded)
    }

    @Test
    fun `long-running tools retain their existing budgets`() {
        assertEquals(610_000L, ToolDescriptorRegistry.descriptorFor("workspace_shell").timeoutMillis)
        assertEquals(125_000L, ToolDescriptorRegistry.descriptorFor("explore_subagent").timeoutMillis)
        assertEquals(30_000L, ToolDescriptorRegistry.descriptorFor("search_web").timeoutMillis)
        assertEquals(30_000L, ToolDescriptorRegistry.descriptorFor("future_tool").timeoutMillis)
    }

    @Test
    fun `screen time is not described as side effect free`() {
        assertEquals(ToolSideEffect.EXTERNAL, ToolDescriptorRegistry.descriptorFor("get_screen_time").sideEffect)
    }

    @Test
    fun `legacy providers receive descriptors through registry adapter`() = runBlocking {
        val provider = object : ToolProvider {
            override val order: Int = 0

            override fun isEnabled(ctx: ToolResolveContext) = true

            override suspend fun provide(ctx: ToolResolveContext) = listOf(
                Tool(
                    name = "third_party_tool",
                    description = "test",
                    execute = { listOf(UIMessagePart.Text("ok")) },
                )
            )
        }
        val assistant = Assistant(id = Uuid.random())
        val context = ToolResolveContext(
            settings = Settings(),
            assistant = assistant,
            conversation = Conversation.ofId(Uuid.random(), assistant.id),
            mode = AgentMode.CHAT,
        )

        val resolved = ToolRegistry(listOf(provider)).resolveWithDescriptors(context)

        assertEquals("third_party_tool", resolved.single().tool.name)
        assertEquals(ToolCapability.UNKNOWN, resolved.single().descriptor.capability)
        assertEquals(ToolDefaultApproval.ASK, resolved.single().descriptor.defaultApproval)
    }
}
