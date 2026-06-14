package me.rerere.rikkahub.data.ai.runtime

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_MODEL_NAME
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the issue #286 EXECUTION alias (SPEC.md M3) at the production seam — `AppToolCatalog.tools`
 * for a [TurnMode.Main] turn. The advertised, model-facing spawn name is `agent`
 * ([SPAWN_TOOL_MODEL_NAME]); the legacy `task` ([SPAWN_TOOL_NAME]) is no longer advertised as a
 * distinct capability but is kept as a hidden EXECUTION alias so a turn interrupted mid-spawn —
 * approval pending, app restarted — can REPLAY its persisted `UIMessagePart.Tool(toolName = "task")`
 * and still resolve. `ChatTurnRuntime` resolves a pending call by EXACT name
 * (`find { it.name == toolName }`); without a `task`-named entry in the assembled pool that replay
 * throws "Tool task not found".
 *
 * Where [AppToolCatalogPolicyTest] pins these facts with hand-picked examples, this test pins them
 * as PROPERTIES over arbitrary surrounding pools (random base tools, random MCP servers, the full
 * board `task_*` family, lookalikes): no matter what else is in the pool, a fresh Main turn that
 * includes the spawn tool resolves BOTH `agent` and the legacy `task` to the SAME spawn `execute`
 * (reference identity — the alias is the spawn impl via `Tool.copy(name = …)`, NOT the board
 * family's `task_create`), advertises `agent` + all four `task_*`, and an `includeSpawnTool=false`
 * Main turn adds no alias while every Subagent pool contains neither spawn name.
 *
 * A failing property here is a real userspace break (a stored/in-flight `task` call that no longer
 * executes, or a recursion guard that leaked a spawn tool into a child) — fix the source, never
 * weaken the assertion.
 */
class SpawnToolAliasPropertyTest {

    /** The full work-board family — present in EVERY pool (Main and Subagent), must always survive. */
    private val boardFamily = listOf("task_create", "task_get", "task_list", "task_update")

    /**
     * Names that merely RESEMBLE a spawn name but are not — the board family, the `mcp__`-prefixed
     * impersonators, plurals, alternate casing. None may ever be confused for the spawn alias, and
     * none may be stripped from a subagent pool.
     */
    private val lookalikes = boardFamily + listOf("mcp__agent", "mcp__task", "agents", "tasks", "Task")

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

    private fun tool(name: String): Tool =
        Tool(name = name, description = "", execute = { emptyList() })

    /**
     * The production-shaped spawn factory output: the real spawn tool advertises under the
     * model-facing name `agent` after the rename. The catalog mints the hidden legacy `task` alias
     * from THIS tool via `Tool.copy(name = SPAWN_TOOL_NAME)`, so the stub must NOT be named `task`
     * itself — otherwise the alias entry would be indistinguishable from the advertised one and the
     * execute-identity check below could not tell the alias apart from the board family.
     */
    private fun spawnToolStub(): Tool =
        Tool(name = SPAWN_TOOL_MODEL_NAME, description = "", execute = { emptyList() })

    private fun catalog(
        baseTools: suspend (AssistantConfig, TurnMode) -> List<Tool>,
        serverTools: Map<Uuid, McpTool> = emptyMap(),
        spawnTool: (parentModelId: Uuid?) -> Tool? = { spawnToolStub() },
    ): AppToolCatalog = AppToolCatalog(
        baseTools = baseTools,
        mcpToolsForAssistant = { target ->
            target.mcpServers.mapNotNull { id -> serverTools[id]?.let { id to it } }
        },
        mcpCall = { _, _, _ -> emptyList<UIMessagePart>() },
        spawnTool = spawnTool,
        // The board family rides the BASE pool in production, but the spawn alias must be
        // distinguishable from it; supply it via the dedicated board seam so the pool shape matches
        // production (board tools in EVERY turn, parent and subagent — decision #5).
        boardTools = { boardFamily.map { tool(it) } },
    )

    /**
     * Arbitrary surrounding base pool: random names plus the lookalike set, shuffled. The two exact
     * spawn names are NEVER seeded into the base pool here — the only spawn-named entries in a Main
     * pool must come from the spawn factory + its alias, so the identity check is unambiguous.
     */
    private val arbBasePool: Arb<List<String>> = Arb.list(Arb.string(1..6).map { it }, 0..6)
        .map { random -> (random.filterNot { it == SPAWN_TOOL_MODEL_NAME || it == SPAWN_TOOL_NAME } + lookalikes).shuffled() }

    private fun mainCtx(target: AssistantConfig, includeSpawnTool: Boolean) = ToolAssemblyContext(
        mode = TurnMode.Main,
        targetAssistant = target,
        parentModelId = null,
        allowApprovalTools = true,
        includeSpawnTool = includeSpawnTool,
    )

    private fun subagentCtx(target: AssistantConfig) = ToolAssemblyContext(
        mode = TurnMode.Subagent,
        targetAssistant = target,
        parentModelId = Uuid.random(),
        allowApprovalTools = false,
        includeSpawnTool = false,
    )

    /**
     * The load-bearing property (Success Criteria #4): on a fresh Main turn, a `find { it.name ==
     * "task" }` lookup — exactly how `ChatTurnRuntime` resolves a replayed pending call — resolves to
     * a tool whose `execute` is the SAME reference as the advertised `agent` tool's execute. So an
     * old `toolName="task"` call runs the exact spawn impl; there is no "Tool task not found", and the
     * alias is provably the spawn tool, not the board family's `task_create`.
     */
    @Test
    fun `legacy task lookup resolves to the advertised agent execute on every main turn`() {
        runBlocking {
            checkAll(300, arbBasePool) { names ->
                val cat = catalog(baseTools = { _, _ -> names.map { tool(it) } })
                val pool = cat.tools(mainCtx(assistant(Uuid.random(), emptySet()), includeSpawnTool = true))

                val agentTool = pool.find { it.name == SPAWN_TOOL_MODEL_NAME }
                val taskTool = pool.find { it.name == SPAWN_TOOL_NAME }

                assertNotNull("fresh `agent` call must resolve in the main pool", agentTool)
                assertNotNull(
                    "replayed legacy `task` call must resolve in the main pool (no \"Tool task not found\")",
                    taskTool,
                )
                // Reference identity: Tool.copy(name = …) preserves the execute reference, so the
                // alias runs the SAME spawn impl. This is what distinguishes the alias from the board
                // family — `task_create`'s execute is a different reference entirely.
                assertTrue(
                    "legacy `task` alias shares the advertised `agent` execute (the spawn impl, not the board family)",
                    agentTool!!.execute === taskTool!!.execute,
                )
            }
        }
    }

    /**
     * The `task` alias is the SPAWN family, not the BOARD family (Success Criteria #1). Its execute
     * must differ — by reference — from every `task_*` board tool's execute, so the model/runtime can
     * never conflate the legacy spawn alias with the board's "create" verb.
     */
    @Test
    fun `legacy task alias is distinct from the board task_ family by execute identity`() {
        runBlocking {
            checkAll(300, arbBasePool) { names ->
                val cat = catalog(baseTools = { _, _ -> names.map { tool(it) } })
                val pool = cat.tools(mainCtx(assistant(Uuid.random(), emptySet()), includeSpawnTool = true))

                val taskAlias = pool.find { it.name == SPAWN_TOOL_NAME }
                assertNotNull("legacy `task` alias present on a fresh main turn", taskAlias)

                boardFamily.forEach { boardName ->
                    val boardTool = pool.find { it.name == boardName }
                    assertNotNull("board tool `$boardName` advertised on a fresh main turn", boardTool)
                    assertFalse(
                        "legacy `task` alias must NOT share `$boardName`'s execute — it is the spawn impl, not the board family",
                        taskAlias!!.execute === boardTool!!.execute,
                    )
                }
            }
        }
    }

    /**
     * The model-advertised pool on a fresh Main turn contains `agent` AND all four `task_*` board
     * tools (Success Criteria #1, #3). The bare `task` entry that IS present is the spawn alias, not a
     * distinct board capability — its identity is pinned by the two properties above; here we pin
     * mere PRESENCE of the advertised set, over arbitrary surrounding pools.
     */
    @Test
    fun `fresh main turn advertises agent and every board task tool`() {
        runBlocking {
            checkAll(300, arbBasePool) { names ->
                val cat = catalog(baseTools = { _, _ -> names.map { tool(it) } })
                val pool = cat.tools(mainCtx(assistant(Uuid.random(), emptySet()), includeSpawnTool = true))
                val poolNames = pool.map { it.name }

                assertTrue("advertised spawn tool `agent` present", poolNames.contains(SPAWN_TOOL_MODEL_NAME))
                boardFamily.forEach { boardName ->
                    assertTrue("board tool `$boardName` advertised on a fresh main turn", poolNames.contains(boardName))
                }
            }
        }
    }

    /**
     * The alias is added ONLY where the real spawn tool is added (Main + includeSpawnTool). An
     * `includeSpawnTool=false` Main turn must add NEITHER the advertised `agent` NOR the legacy `task`
     * alias — the alias never appears without the tool it aliases — while the board family still
     * rides the base pool. (Boundary case from the Testing Strategy.)
     */
    @Test
    fun `main turn without spawn tool adds neither advertised name nor alias`() {
        runBlocking {
            checkAll(300, arbBasePool) { names ->
                val cat = catalog(baseTools = { _, _ -> names.map { tool(it) } })
                val pool = cat.tools(mainCtx(assistant(Uuid.random(), emptySet()), includeSpawnTool = false))

                assertNull(
                    "advertised `agent` absent when the spawn tool is not included",
                    pool.find { it.name == SPAWN_TOOL_MODEL_NAME },
                )
                assertNull(
                    "legacy `task` alias absent when the spawn tool is not included",
                    pool.find { it.name == SPAWN_TOOL_NAME },
                )
                // The board family is unaffected by includeSpawnTool — it rides the base pool.
                boardFamily.forEach { boardName ->
                    assertNotNull(
                        "board tool `$boardName` still advertised when spawn tool is excluded",
                        pool.find { it.name == boardName },
                    )
                }
            }
        }
    }

    /**
     * Every Subagent pool contains NEITHER spawn name (Success Criteria #2): the alias is never added
     * on a subagent turn, and the recursion guard ([me.rerere.rikkahub.data.ai.subagent.stripSpawnTools])
     * strips both names belt-and-suspenders even if the spawn factory yields a tool. The board family
     * and every lookalike survive untouched — only the two exact spawn names are reserved.
     */
    @Test
    fun `subagent pool contains neither spawn name and keeps the board family`() {
        runBlocking {
            checkAll(300, arbBasePool) { names ->
                val cat = catalog(baseTools = { _, _ -> names.map { tool(it) } })
                val pool = cat.tools(subagentCtx(assistant(Uuid.random(), emptySet())))
                val poolNames = pool.map { it.name }

                assertFalse("advertised `agent` absent from a subagent pool", poolNames.contains(SPAWN_TOOL_MODEL_NAME))
                assertFalse("legacy `task` alias absent from a subagent pool", poolNames.contains(SPAWN_TOOL_NAME))
                boardFamily.forEach { boardName ->
                    assertTrue("board tool `$boardName` survives on a subagent pool", poolNames.contains(boardName))
                }
                // Lookalikes seeded into the base pool survive the depth-1 strip — only the two exact
                // spawn names are reserved.
                lookalikes.filter { it in names }.forEach { look ->
                    assertTrue("lookalike `$look` survives the subagent strip", poolNames.contains(look))
                }
            }
        }
    }
}
