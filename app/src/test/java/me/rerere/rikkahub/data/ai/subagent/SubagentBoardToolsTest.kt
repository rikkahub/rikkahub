package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the production wiring of finding #1: a SPAWNED subagent's tool pool must include the
 * per-conversation board tools so it can `task_list` / `task_update` the SHARED board (spec
 * assumption 5 / decision #5). The catalog already adds them on a `TurnMode.Subagent` pool, but the
 * production spawn path assembles the subagent pool through [buildSpawnTool]'s `buildSubagentTools`
 * lambda (never the catalog), so the assembly seam itself must add them. [subagentBoardTools] is
 * that seam — pure and Android-free, driven here over a JVM DAO fake (no Room).
 */
class SubagentBoardToolsTest {

    private val conversationId = Uuid.random()
    private val sub = Assistant(name = "Researcher", chatModelId = Uuid.random(), spawnable = true)

    private fun repository(dao: FakeWorkItemDAO) = TaskBoardRepository(
        dao = dao,
        transactions = FakeBoardTransactions(),
        now = { 1_000L },
    )

    @Test
    fun `exposes the four shared board tools to a spawned subagent`() {
        val tools = subagentBoardTools(
            repository = repository(FakeWorkItemDAO()),
            conversationId = conversationId,
            sub = sub,
        )

        assertEquals(
            setOf("task_create", "task_get", "task_list", "task_update"),
            tools.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a subagent task_create lands on its conversation's shared board`() {
        val dao = FakeWorkItemDAO()
        val repository = repository(dao)
        val tools = subagentBoardTools(repository, conversationId, sub)
        val create = tools.single { it.name == "task_create" }

        runBlocking {
            create.execute(buildJsonObject { put("subject", "shared work") })
        }

        // The item the subagent created must be visible on the SAME conversation board the parent
        // and UI read — proving the tool is bound to the shared conversation scope, not a private one.
        val onBoard = runBlocking { repository.list(conversationId, statuses = null) }
        assertEquals(1, onBoard.size)
        assertEquals("shared work", onBoard.single().item.subject)
    }

    @Test
    fun `a subagent can claim a shared board item as the subagent actor`() {
        val dao = FakeWorkItemDAO()
        val repository = repository(dao)
        val item = runBlocking {
            (repository.create(conversationId, WorkItemDraft(subject = "to claim")) as BoardMutationResult.Accepted)
                .snapshot.item.id
        }
        val update = subagentBoardTools(repository, conversationId, sub)
            .single { it.name == "task_update" }

        runBlocking {
            update.execute(buildJsonObject {
                put("id", item.toString())
                put("action", "claim")
            })
        }

        // The claim is owned (decision #4 requires a non-null actor for a claim); without a subagent
        // actor binding the repository would reject the claim and the item would stay unowned.
        val owner = runBlocking { repository.get(conversationId, item)!!.item.ownerHandleId }
        assertTrue("a subagent claim must be accepted and owned", owner != null)
    }
}
