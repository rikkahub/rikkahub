package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_MODEL_NAME
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
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

    // Production-shaped: the real spawn tool advertises under the model-facing name `agent`
    // (SPAWN_TOOL_MODEL_NAME) after the issue #286 rename. The catalog adds the hidden legacy `task`
    // alias from THIS tool via Tool.copy(name = SPAWN_TOOL_NAME), so the stub must NOT be named `task`
    // itself or the alias entry would be indistinguishable from the advertised one.
    private fun spawnToolStub(): Tool =
        Tool(name = SPAWN_TOOL_MODEL_NAME, description = "", execute = { emptyList() })

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
        assertTrue("advertised spawn tool `agent` present in main turn", names.contains(SPAWN_TOOL_MODEL_NAME))
        assertTrue("legacy spawn alias `task` present in main turn", names.contains(SPAWN_TOOL_NAME))
    }

    /**
     * The main pool carries the spawn tool under BOTH the advertised model-facing name `agent`
     * ([SPAWN_TOOL_MODEL_NAME]) AND a hidden legacy alias `task` ([SPAWN_TOOL_NAME]) — the issue #286
     * dual-presence decision. Resolution by exact name (`find { it.name == toolName }`, the way
     * `ChatTurnRuntime` resolves a pending call) must therefore succeed for a fresh `agent` call AND
     * a replayed pending `task` call from a pre-rename transcript, with no "Tool task not found".
     * The alias is the SAME spawn tool, not the board family's `task_create` — verified by execute
     * IDENTITY, not just name (`Tool.copy(name = …)` preserves the execute reference), so the legacy
     * `task` lookup runs the exact spawn impl rather than an impostor.
     */
    @Test
    fun mainPoolResolvesBothSpawnNamesToSameExecute() = runBlocking {
        val catalog = catalog(emptyMap())
        val main = assistant(Uuid.random(), mcpServers = emptySet())

        val pool = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = main,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        )

        // The exact-name resolution ChatTurnRuntime performs for a pending tool call.
        val agentTool = pool.find { it.name == SPAWN_TOOL_MODEL_NAME }
        val taskTool = pool.find { it.name == SPAWN_TOOL_NAME }

        assertTrue("fresh `agent` call resolves in the main pool", agentTool != null)
        assertTrue("replayed legacy `task` call resolves in the main pool", taskTool != null)
        // The alias is the spawn impl itself, not a board-family lookalike: data-class copy keeps the
        // execute reference, so identity equality proves the legacy call runs the same spawn execute.
        assertTrue(
            "legacy `task` alias shares the advertised tool's execute (same spawn impl, not the board family)",
            agentTool!!.execute === taskTool!!.execute,
        )
    }

    /**
     * The alias is added ONLY where the real spawn tool is added (Main + includeSpawnTool). A
     * subagent pool must contain NEITHER spawn name — the recursion guard strips both — so the alias
     * must never leak into a subagent turn even when the spawn factory yields a tool. Verified by
     * feeding a production-shaped spawn stub (named `agent`) and asserting the subagent pool drops
     * both `agent` and the legacy `task` alias.
     */
    @Test
    fun legacyAliasAbsentFromSubagentPool() = runBlocking {
        val catalog = catalog(emptyMap())
        val sub = assistant(Uuid.random(), mcpServers = emptySet())

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse("advertised `agent` absent from subagent pool", names.contains(SPAWN_TOOL_MODEL_NAME))
        assertFalse("legacy `task` alias absent from subagent pool", names.contains(SPAWN_TOOL_NAME))
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

    /**
     * The recursion guard must strip BOTH spawn-tool names from a subagent pool — the advertised
     * `agent` ([SPAWN_TOOL_MODEL_NAME]) AND the legacy execution alias `task` ([SPAWN_TOOL_NAME]).
     * After the `task` -> `agent` rename (issue #286), a base tool literally named `agent` could
     * otherwise hand a subagent a spawn-capable tool under the new name, letting it spawn recursively
     * and defeating TASK_DEPTH_ONE. The work-board `task_*` family (and any other lookalike) must NOT
     * be touched — only the two exact spawn names are reserved.
     */
    @Test
    fun subagentTurnStripsBothSpawnNamesButKeepsTaskFamily() = runBlocking {
        val catalog = catalog(
            serverTools = emptyMap(),
            baseTools = { _, _ ->
                listOf(
                    tool(SPAWN_TOOL_MODEL_NAME),
                    tool(SPAWN_TOOL_NAME),
                    tool("task_create"),
                    tool("task_get"),
                    tool("task_list"),
                    tool("task_update"),
                    tool("local_read"),
                )
            },
        )
        val sub = assistant(Uuid.random(), mcpServers = emptySet())

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse("advertised spawn name `agent` stripped on subagent pool", names.contains(SPAWN_TOOL_MODEL_NAME))
        assertFalse("legacy spawn alias `task` stripped on subagent pool", names.contains(SPAWN_TOOL_NAME))
        assertTrue("board task_create survives", names.contains("task_create"))
        assertTrue("board task_get survives", names.contains("task_get"))
        assertTrue("board task_list survives", names.contains("task_list"))
        assertTrue("board task_update survives", names.contains("task_update"))
        assertTrue("non-spawn base tool retained", names.contains("local_read"))
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
     * named with EITHER spawn name — the advertised `agent` ([SPAWN_TOOL_MODEL_NAME]) or the legacy
     * alias `task` ([SPAWN_TOOL_NAME]) — must be stripped on ANY subagent context, even one that
     * allows approval tools — production strips spawn unconditionally on every subagent pool
     * (SubagentRunner -> filterToolsForSubagent), independent of approval stripping. Coupling the
     * two would leak either tool whenever `allowApprovalTools` is true.
     */
    @Test
    fun spawnNamedBaseToolStrippedOnSubagentEvenWhenApprovalAllowed() = runBlocking {
        val catalog = catalog(
            serverTools = emptyMap(),
            baseTools = { _, _ ->
                listOf(tool("local_read"), tool(SPAWN_TOOL_MODEL_NAME), tool(SPAWN_TOOL_NAME))
            },
        )
        val sub = assistant(Uuid.random(), mcpServers = emptySet())

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                // The orthogonality boundary: a subagent context that nonetheless allows approval
                // tools must STILL strip both recursion-guarded spawn names.
                allowApprovalTools = true,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse("advertised `agent` spawn name stripped on subagent pool", names.contains(SPAWN_TOOL_MODEL_NAME))
        assertFalse("legacy `task` spawn alias stripped on subagent pool", names.contains(SPAWN_TOOL_NAME))
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
