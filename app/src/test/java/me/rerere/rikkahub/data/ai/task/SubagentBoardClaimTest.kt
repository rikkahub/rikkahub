package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.board.WorkItemAction
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.repository.TaskBoardRepository
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Two fake spawned subagents work ONE shared per-conversation board (SPEC.md M5/T11,
 * maintainer decision #5).
 *
 * What this pins, that earlier milestones did not:
 *  - A spawned subagent's board tools are bound to the PARENT conversation's board, with the
 *    claim owner = the subagent's execution handle id (not the parent-conversation actor). The
 *    binding flows through [BoardPortAdapter.forHandle], the same per-conversation
 *    [TaskBoardRepository] the board UI uses (decision #4), so legality is repository-enforced.
 *  - SingleOwnerClaim holds under CONCURRENT subagent claims of the same item: exactly one
 *    handle wins ownership.
 *  - A single subagent handle may hold MULTIPLE claims (decision #5: no per-owner cap).
 *  - Accepted claims are tracked back onto the [ExecutionHandleRegistry] as the handle's
 *    `workItemIds`, so orphan recovery (M6) can later release every claim a dead handle owns.
 *    Claims/leases persist in the repository (Room in production, the fake DAO here); the live
 *    coroutine handle stays in-memory in the registry.
 */
class SubagentBoardClaimTest {

    private val conversationId = Uuid.random()

    private class Fixture {
        val dao = FakeWorkItemDAO()
        val repository = TaskBoardRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            now = { 1_000L },
        )
        val registry = ExecutionHandleRegistry()
    }

    private fun Fixture.seedItem(subject: String): Uuid = Uuid.random().also { id ->
        runBlocking {
            dao.insert(
                WorkItemEntity(
                    id = id.toString(),
                    conversationId = conversationId.toString(),
                    subject = subject,
                    status = WorkItemStatus.Pending.name,
                    ownerHandleId = null,
                    ownerName = null,
                    leaseExpiresAt = null,
                    createdAt = 0L,
                    updatedAt = 0L,
                )
            )
        }
    }

    /** Spawn a fake subagent handle and bind a handle-scoped board port over the shared board. */
    private fun Fixture.subagent(parentJob: Job): Pair<ExecutionHandle, BoardPortAdapter> {
        val handle = registry.register(
            conversationId = conversationId,
            assistantId = Uuid.random(),
            parentJob = parentJob,
        )
        val board = BoardPortAdapter.forHandle(
            repository = repository,
            conversationId = conversationId,
            registry = registry,
            handle = handle,
        )
        return handle to board
    }

    @Test
    fun subagentClaim_ownerIsTheExecutionHandle_andRegistryTracksTheItem(): Unit = runBlocking {
        val fx = Fixture()
        val parentJob = Job()
        val item = fx.seedItem("shared-work")
        val (handle, board) = fx.subagent(parentJob)

        val result = board.update(WorkItemPatch(id = item, action = WorkItemAction.Claim))
        assertTrue("subagent claim must be accepted", result is BoardMutationResult.Accepted)

        val snapshot = fx.repository.get(conversationId, item)!!
        assertEquals(WorkItemStatus.InProgress, snapshot.item.status)
        assertEquals("owner must be the subagent's execution handle id", handle.id, snapshot.item.ownerHandleId)
        assertTrue(
            "registry must track the claimed item on the owning handle",
            fx.registry.get(handle.id)!!.workItemIds.contains(item),
        )

        parentJob.cancel()
    }

    @Test
    fun concurrentSubagentClaims_yieldExactlyOneOwner(): Unit = runBlocking {
        val fx = Fixture()
        val parentJob = Job()
        val item = fx.seedItem("contested")
        val (handleA, boardA) = fx.subagent(parentJob)
        val (handleB, boardB) = fx.subagent(parentJob)

        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) { boardA.update(WorkItemPatch(id = item, action = WorkItemAction.Claim)) },
                async(Dispatchers.Default) { boardB.update(WorkItemPatch(id = item, action = WorkItemAction.Claim)) },
            ).awaitAll()
        }

        val accepted = results.count { it is BoardMutationResult.Accepted }
        assertEquals("exactly one of two concurrent subagent claims must win", 1, accepted)

        val owner = fx.repository.get(conversationId, item)!!.item.ownerHandleId
        assertTrue("owner must be one of the two competing handles", owner == handleA.id || owner == handleB.id)

        // The winning handle tracks the item; the loser does not.
        val winner = if (owner == handleA.id) handleA else handleB
        val loser = if (owner == handleA.id) handleB else handleA
        assertTrue(fx.registry.get(winner.id)!!.workItemIds.contains(item))
        assertTrue(fx.registry.get(loser.id)!!.workItemIds.isEmpty())

        parentJob.cancel()
    }

    @Test
    fun oneSubagentHandleMayHoldMultipleClaims(): Unit = runBlocking {
        val fx = Fixture()
        val parentJob = Job()
        val items = (1..4).map { fx.seedItem("item-$it") }
        val (handle, board) = fx.subagent(parentJob)

        items.forEach { item ->
            val result = board.update(WorkItemPatch(id = item, action = WorkItemAction.Claim))
            assertTrue("multi-claim per subagent is allowed (decision #5)", result is BoardMutationResult.Accepted)
        }

        items.forEach { item ->
            assertEquals(handle.id, fx.repository.get(conversationId, item)!!.item.ownerHandleId)
        }
        assertEquals(
            "the handle must track every claimed item",
            items.toSet(),
            fx.registry.get(handle.id)!!.workItemIds,
        )

        parentJob.cancel()
    }

    @Test
    fun twoSubagentsCompleteOverlappingItems_eachOwnsExactlyWhatItClaimed(): Unit = runBlocking {
        val fx = Fixture()
        val parentJob = Job()
        // Three items: A claims+completes #0 and #1; B claims #1 (contested) and #2.
        val items = (0..2).map { fx.seedItem("overlap-$it") }
        val (handleA, boardA) = fx.subagent(parentJob)
        val (handleB, boardB) = fx.subagent(parentJob)

        // Both race for item[1]; A and B each take their private items uncontested.
        val claims = coroutineScope {
            listOf(
                async(Dispatchers.Default) { boardA.update(WorkItemPatch(id = items[0], action = WorkItemAction.Claim)) },
                async(Dispatchers.Default) { boardA.update(WorkItemPatch(id = items[1], action = WorkItemAction.Claim)) },
                async(Dispatchers.Default) { boardB.update(WorkItemPatch(id = items[1], action = WorkItemAction.Claim)) },
                async(Dispatchers.Default) { boardB.update(WorkItemPatch(id = items[2], action = WorkItemAction.Claim)) },
            ).awaitAll()
        }
        assertEquals("only one of the two contested claims may win", 3, claims.count { it is BoardMutationResult.Accepted })

        // Each item has exactly one owner, drawn from the competing handles.
        items.forEach { item ->
            val owner = fx.repository.get(conversationId, item)!!.item.ownerHandleId
            assertTrue("every claimed item has exactly one owner", owner == handleA.id || owner == handleB.id)
        }

        // Owners complete their own claims; completion keeps the owner for display.
        items.forEach { item ->
            val owner = fx.repository.get(conversationId, item)!!.item.ownerHandleId
            val board = if (owner == handleA.id) boardA else boardB
            assertTrue(board.update(WorkItemPatch(id = item, action = WorkItemAction.Complete)) is BoardMutationResult.Accepted)
            assertEquals(WorkItemStatus.Completed, fx.repository.get(conversationId, item)!!.item.status)
        }

        // Registry tracking partitions the items by their winning owner with no overlap.
        val aItems = fx.registry.get(handleA.id)!!.workItemIds
        val bItems = fx.registry.get(handleB.id)!!.workItemIds
        assertTrue("a handle never tracks an item it did not win", aItems.intersect(bItems).isEmpty())
        assertEquals("every item is tracked by exactly one handle", items.toSet(), aItems + bItems)

        parentJob.cancel()
    }

    @Test
    fun nonClaimActions_doNotTrackOnTheHandle(): Unit = runBlocking {
        val fx = Fixture()
        val parentJob = Job()
        val item = fx.repository.create(conversationId, WorkItemDraft(subject = "fresh"))
            .let { (it as BoardMutationResult.Accepted).snapshot.item.id }
        val (handle, board) = fx.subagent(parentJob)

        // A pure metadata edit (no claim) must not attach the item to the handle.
        val edit = board.update(WorkItemPatch(id = item, description = "edited"))
        assertTrue(edit is BoardMutationResult.Accepted)
        assertTrue(
            "only an accepted claim attaches an item to the owning handle",
            fx.registry.get(handle.id)!!.workItemIds.isEmpty(),
        )

        parentJob.cancel()
    }
}
