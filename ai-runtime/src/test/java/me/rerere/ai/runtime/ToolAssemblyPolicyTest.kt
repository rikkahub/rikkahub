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
 * Contract-level guard for the tool-assembly POLICY the runtime depends on (issue #243 §D). Drives a
 * FAKE [ToolCatalog] that encodes the same rules the production app catalog reproduces, so the
 * neutral [ToolAssemblyContext] surface (mode / allowApprovalTools / includeSpawnTool /
 * targetAssistant) is pinned without leaking any app type into `:ai-runtime`. Pure JVM, no Android.
 *
 * The app-side counterpart (`AppToolCatalogPolicyTest`, run under `:app`) pins the REAL adapter
 * against the production policy; this test pins the contract the runtime is written against.
 */
class ToolAssemblyPolicyTest {

    // The spawn tool's well-known name (mirrors the app `SPAWN_TOOL_NAME = "task"`). Named only in
    // test sources so the §E P6 main-token invariant stays green.
    private val spawnToolName = "task"

    private fun assistant(id: Uuid, mcpServers: Set<Uuid>): AssistantConfig = AssistantConfig(
        id = id,
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

    private fun tool(name: String, needsApproval: Boolean = false): Tool =
        Tool(name = name, description = "", needsApproval = needsApproval, execute = { emptyList() })

    /**
     * A fake catalog encoding the §D policy: an approval-gated tool, an MCP tool whose name carries
     * the `mcp__` prefix (selected per the TARGET assistant's mcpServers), and the spawn tool. The
     * three policy knobs gate exactly as the production adapter does.
     */
    private inner class PolicyFakeCatalog(
        private val mcpServersWithTools: Set<Uuid>,
    ) : ToolCatalog {
        override suspend fun tools(ctx: ToolAssemblyContext): List<Tool> = buildList {
            add(tool("local_search"))
            add(tool("dangerous_write", needsApproval = true))
            // MCP tools selected off the TARGET assistant's mcpServers (§C3 by-target-assistant).
            if (ctx.targetAssistant.mcpServers.any { it in mcpServersWithTools }) {
                add(tool("mcp__remote_call"))
            }
            if (ctx.includeSpawnTool && ctx.mode == TurnMode.Main) {
                add(tool(spawnToolName))
            }
        }.let { pool ->
            // Subagent / approval-disabled turns strip approval-gated tools (§D).
            if (ctx.allowApprovalTools) pool else pool.filterNot { it.needsApproval }
        }
    }

    @Test
    fun mainTurnKeepsApprovalAndSpawnTools() = runBlocking {
        val serverId = Uuid.random()
        val asst = assistant(Uuid.random(), mcpServers = setOf(serverId))
        val catalog = PolicyFakeCatalog(mcpServersWithTools = setOf(serverId))

        val pool = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = asst,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        )
        val names = pool.map { it.name }

        assertTrue("approval tool present in main turn", names.contains("dangerous_write"))
        assertTrue("spawn tool present in main turn", names.contains(spawnToolName))
        assertTrue("mcp tool present + prefixed", names.contains("mcp__remote_call"))
    }

    @Test
    fun subagentTurnStripsApprovalAndSpawnTools() = runBlocking {
        val serverId = Uuid.random()
        val asst = assistant(Uuid.random(), mcpServers = setOf(serverId))
        val catalog = PolicyFakeCatalog(mcpServersWithTools = setOf(serverId))

        val pool = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = asst,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        )
        val names = pool.map { it.name }

        assertFalse("approval tool stripped in subagent turn", names.contains("dangerous_write"))
        assertFalse("spawn tool absent in subagent turn (recursion guard)", names.contains(spawnToolName))
        // The sub still gets its own (target-selected, non-approval) tools.
        assertTrue("subagent keeps non-approval mcp tool", names.contains("mcp__remote_call"))
    }

    @Test
    fun spawnToolAbsentWhenIncludeFalseEvenInMain() = runBlocking {
        val asst = assistant(Uuid.random(), mcpServers = emptySet())
        val catalog = PolicyFakeCatalog(mcpServersWithTools = emptySet())

        val pool = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = asst,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = false,
            )
        )

        assertFalse(pool.map { it.name }.contains(spawnToolName))
    }
}
