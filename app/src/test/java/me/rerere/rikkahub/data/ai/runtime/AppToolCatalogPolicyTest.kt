package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.ai.runtime.subagent.SPAWN_TOOL_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression guard pinning the REAL [AppToolCatalog] against the production tool-assembly policy
 * (issue #243 slice 3, mirrors `ChatService.buildGenerationTools`). Where `ToolAssemblyPolicyTest`
 * (in `:ai-runtime`) pins the contract with a fake catalog, this test drives the production adapter
 * with fake LocalTools / Mcp / spawn seams so a regression that breaks the four invariants reddens
 * the real adapter, not just a stand-in.
 *
 * Invariants pinned:
 *  - MCP tools are `mcp__`-prefixed (via the real `mapMcpTool` seam).
 *  - MCP selection keys off `ctx.targetAssistant` (subagent does not inherit the parent's servers).
 *  - `needsApproval` tools are stripped on a subagent turn (allowApprovalTools=false).
 *  - the spawn tool is absent on a subagent turn and when `includeSpawnTool` is false (recursion
 *    guard), present on a Main turn that includes it.
 */
class AppToolCatalogPolicyTest {

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

    private fun spawnToolStub(): Tool =
        Tool(name = SPAWN_TOOL_NAME, description = "", execute = { emptyList() })

    // serverId -> the McpTool that server exposes.
    private fun catalog(
        serverTools: Map<Uuid, McpTool>,
        baseTools: suspend (AssistantConfig, TurnMode) -> List<Tool> = { _, _ ->
            listOf(tool("local_read"), tool("dangerous_write", needsApproval = true))
        },
        spawnTool: (parentModelId: Uuid?) -> Tool? = { spawnToolStub() },
    ): AppToolCatalog = AppToolCatalog(
        baseTools = baseTools,
        mcpToolsForAssistant = { target ->
            // The REAL by-target selection idiom: only servers in the TARGET assistant's set.
            target.mcpServers.mapNotNull { id -> serverTools[id]?.let { id to it } }
        },
        mcpCall = { _, _, _ -> emptyList<UIMessagePart>() },
        spawnTool = spawnTool,
    )

    @Test
    fun mainTurnPrefixesMcpAndKeepsApprovalAndSpawn() = runBlocking {
        val serverA = Uuid.random()
        val catalog = catalog(mapOf(serverA to McpTool(name = "remote_call")))
        val asst = assistant(Uuid.random(), mcpServers = setOf(serverA))

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = asst,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        ).map { it.name }

        assertTrue("mcp tool carries mcp__ prefix", names.contains("mcp__remote_call"))
        assertTrue("approval tool present in main turn", names.contains("dangerous_write"))
        assertTrue("spawn tool present in main turn", names.contains(SPAWN_TOOL_NAME))
    }

    @Test
    fun subagentTurnStripsApprovalAndSpawn() = runBlocking {
        val serverA = Uuid.random()
        val catalog = catalog(mapOf(serverA to McpTool(name = "remote_call")))
        val sub = assistant(Uuid.random(), mcpServers = setOf(serverA))

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse("approval tool stripped on subagent turn", names.contains("dangerous_write"))
        assertFalse("spawn tool absent on subagent turn", names.contains(SPAWN_TOOL_NAME))
        assertTrue("non-approval mcp tool retained", names.contains("mcp__remote_call"))
    }

    @Test
    fun mcpSelectionKeysOffTargetNotParent() = runBlocking {
        val serverA = Uuid.random()
        val serverB = Uuid.random()
        val catalog = catalog(
            mapOf(serverA to McpTool(name = "alpha"), serverB to McpTool(name = "beta")),
        )
        val sub = assistant(Uuid.random(), mcpServers = setOf(serverB))

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertTrue("subagent selects its own server's tool", names.contains("mcp__beta"))
        assertFalse("subagent does not inherit parent's server tool", names.contains("mcp__alpha"))
    }

    @Test
    fun spawnAbsentWhenIncludeFalseOnMain() = runBlocking {
        val catalog = catalog(emptyMap())
        val asst = assistant(Uuid.random(), mcpServers = emptySet())

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = asst,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse(names.contains(SPAWN_TOOL_NAME))
    }

    /**
     * The spawn tool's parent model must be the current (main/parent) assistant's own
     * `chatModelId`, not `ctx.parentModelId` (which is null on a main turn). Production passes
     * `parentModelId = assistant.chatModelId` at the spawn site so an UNPINNED subagent inherits
     * the parent's model via `resolveSubagentModel`. If the adapter fed `ctx.parentModelId` here,
     * the spawn tool would be built with null and that inheritance would silently break.
     */
    @Test
    fun spawnToolBuiltWithParentAssistantModelOnMainTurn() = runBlocking {
        val parentModel = Uuid.random()
        var capturedParentModel: Uuid? = null
        var spawnBuilt = false
        val catalog = catalog(
            serverTools = emptyMap(),
            spawnTool = { parentModelId ->
                capturedParentModel = parentModelId
                spawnBuilt = true
                spawnToolStub()
            },
        )
        val main = assistant(Uuid.random(), mcpServers = emptySet()).copy(chatModelId = parentModel)

        catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = main,
                // The subagent-turn carrier is null on a main turn; it must NOT be the spawn parent.
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        )

        assertTrue("spawn tool was built on a main turn", spawnBuilt)
        assertEquals(
            "spawn parent is the main assistant's own model, not the null subagent carrier",
            parentModel,
            capturedParentModel,
        )
    }

    /**
     * The spawn-strip (recursion guard) is orthogonal to the approval-strip. A base tool literally
     * named `task` (== [SPAWN_TOOL_NAME]) must be stripped on ANY subagent context, even one that
     * allows approval tools — production strips spawn unconditionally on every subagent pool
     * (SubagentRunner -> filterToolsForSubagent), independent of approval stripping. Coupling the
     * two would leak this tool whenever `allowApprovalTools` is true.
     */
    @Test
    fun spawnNamedBaseToolStrippedOnSubagentEvenWhenApprovalAllowed() = runBlocking {
        val catalog = catalog(
            serverTools = emptyMap(),
            baseTools = { _, _ -> listOf(tool("local_read"), tool(SPAWN_TOOL_NAME)) },
        )
        val sub = assistant(Uuid.random(), mcpServers = emptySet())

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                // The orthogonality boundary: a subagent context that nonetheless allows approval
                // tools must STILL strip the recursion-guarded `task` tool.
                allowApprovalTools = true,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse("spawn-named base tool stripped on subagent pool", names.contains(SPAWN_TOOL_NAME))
        assertTrue("non-spawn base tool retained", names.contains("local_read"))
    }

    /**
     * The recursion guard keys on the turn MODE alone. Production never filters the MAIN pool —
     * the guard lives only in the subagent path (SubagentRunner -> filterToolsForSubagent). A main
     * turn that merely omits the spawn tool (includeSpawnTool=false) must NOT strip a base tool
     * that happens to be named `task`; gating the strip on `!includeSpawnTool` would bake that
     * divergence into the pool slice 10 rewires ChatService onto.
     */
    @Test
    fun spawnNamedBaseToolRetainedOnMainTurnWithoutSpawnTool() = runBlocking {
        val catalog = catalog(
            serverTools = emptyMap(),
            baseTools = { _, _ -> listOf(tool("local_read"), tool(SPAWN_TOOL_NAME)) },
            // The spawn seam must not even be consulted; returning null keeps the add-path inert
            // so the only way SPAWN_TOOL_NAME can appear is via the base pool.
            spawnTool = { null },
        )
        val main = assistant(Uuid.random(), mcpServers = emptySet())

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = main,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertTrue("main pool is never recursion-filtered", names.contains(SPAWN_TOOL_NAME))
        assertTrue("non-spawn base tool retained", names.contains("local_read"))
    }
}
