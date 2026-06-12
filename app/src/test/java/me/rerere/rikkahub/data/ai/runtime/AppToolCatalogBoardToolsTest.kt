package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.BoardItemSnapshot
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.TaskBoardPort
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.ai.runtime.mcp.McpTool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.repository.BoardActor
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.ai.task.BoardPortAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins SPEC.md M3 (T7): the board tools (`task_create/get/list/update`) land in the
 * [AppToolCatalog] BASE POOL for BOTH [TurnMode.Main] and [TurnMode.Subagent] — a subagent must be
 * able to coordinate over the shared board (spec assumption 5) — and the per-conversation
 * [BoardPortAdapter] delegates every call to [TaskBoardRepository] with THIS conversation's id, so
 * the board is conversation-scoped at the single repository enforcement point (decision #4), never
 * via a tool-handler filter.
 */
class AppToolCatalogBoardToolsTest {

    private fun assistant(id: Uuid, mcpServers: Set<Uuid> = emptySet()): AssistantConfig = AssistantConfig(
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

    private val boardToolNames = setOf("task_create", "task_get", "task_list", "task_update")

    /** Records the conversation id and patch the port saw, so scoping can be asserted on delegation. */
    private class RecordingBoardPort : TaskBoardPort {
        val created = mutableListOf<WorkItemDraft>()
        override suspend fun create(draft: WorkItemDraft): BoardMutationResult {
            created += draft
            return BoardMutationResult.Rejected("stub")
        }

        override suspend fun get(id: Uuid): BoardItemSnapshot? = null
        override suspend fun list(statuses: Set<WorkItemStatus>?): List<BoardItemSnapshot> = emptyList()
        override suspend fun update(patch: WorkItemPatch): BoardMutationResult =
            BoardMutationResult.Rejected("stub")
    }

    private fun catalog(
        boardPort: TaskBoardPort,
        baseTools: suspend (AssistantConfig, TurnMode) -> List<Tool> = { _, _ -> emptyList() },
    ): AppToolCatalog = AppToolCatalog(
        baseTools = baseTools,
        mcpToolsForAssistant = { _ -> emptyList<Pair<Uuid, McpTool>>() },
        mcpCall = { _, _, _ -> emptyList<UIMessagePart>() },
        spawnTool = { Tool(name = SPAWN_TOOL_NAME, description = "", execute = { emptyList() }) },
        boardTools = { me.rerere.ai.runtime.board.buildBoardTools(boardPort) },
    )

    @Test
    fun boardToolsPresentOnMainTurn() = runBlocking {
        val catalog = catalog(RecordingBoardPort())
        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = assistant(Uuid.random()),
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        ).map { it.name }.toSet()

        assertTrue("board tools must be in the main-turn pool", names.containsAll(boardToolNames))
    }

    @Test
    fun boardToolsPresentOnSubagentTurn() = runBlocking {
        val catalog = catalog(RecordingBoardPort())
        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = assistant(Uuid.random()),
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        ).map { it.name }.toSet()

        // Subagents coordinate over the shared board (spec assumption 5); the board tools do not
        // need approval and are not named `task`, so they survive both the approval strip and the
        // recursion guard — but the catalog must still ADD them to the subagent pool.
        assertTrue("board tools must be in the subagent-turn pool", names.containsAll(boardToolNames))
        assertTrue("recursion guard still strips the spawn tool", SPAWN_TOOL_NAME !in names)
    }

    /**
     * Per-conversation scoping (decision #4): a [BoardPortAdapter] bound to conversation A creates
     * items on A's board only, and a separate adapter for B sees nothing of A's items — the same
     * physical [TaskBoardRepository], two conversation scopes. A scoping bug here would let one
     * conversation's agent read or mutate another conversation's board. Driven through the REAL
     * adapter + repository over a JVM DAO fake (no Room), the single enforcement path used by tools
     * and UI alike.
     */
    @Test
    fun boardPortAdapterScopesEachCallToItsConversation() = runBlocking {
        val dao = me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO()
        val repository = TaskBoardRepository(
            dao = dao,
            transactions = me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions(),
            now = { 1_000L },
        )
        val convA = Uuid.random()
        val convB = Uuid.random()
        val actor = BoardActor(handleId = "ui", displayName = "user")

        val portA = BoardPortAdapter(repository = repository, conversationId = convA, actor = actor)
        val portB = BoardPortAdapter(repository = repository, conversationId = convB, actor = actor)

        val createdOnA = portA.create(WorkItemDraft(subject = "only on A"))
        assertTrue("create on A succeeds", createdOnA is BoardMutationResult.Accepted)

        // A's adapter sees its own item; B's adapter sees an empty board — the scope is the binding.
        assertEquals(1, portA.list(statuses = null).size)
        assertEquals("only on A", portA.list(statuses = null).single().item.subject)
        assertTrue("B's board is empty — A's item is not visible to B", portB.list(statuses = null).isEmpty())

        // B's get of A's item id returns null: the adapter forwards B's conversation id to the repo.
        val aId = (createdOnA as BoardMutationResult.Accepted).snapshot.item.id
        assertNotNull("A can read its own item", portA.get(aId))
        assertEquals("B cannot read A's item by id", null, portB.get(aId))
    }
}
