package me.rerere.rikkahub.data.repository

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.board.WorkItemAction
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.ai.runtime.board.WorkItemTransitionResult
import me.rerere.ai.runtime.board.WorkItemTransitionValidator
import me.rerere.ai.runtime.contract.BoardMutationResult
import me.rerere.ai.runtime.contract.WorkItemDraft
import me.rerere.ai.runtime.contract.WorkItemPatch
import me.rerere.rikkahub.data.db.entity.WorkItemDependencyEntity
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Property tests for [TaskBoardRepository] (SPEC.md M2), against DAO fakes — the repository is
 * the SINGLE enforcement point for every board invariant (maintainer decision #4):
 *
 * - SingleOwnerClaim: under concurrent claims one item ends with exactly one owner; one owner
 *   may hold claims on many items (decision #5).
 * - DeleteUnblocksDependents: deleting a blocker removes its edges, so dependents become
 *   claimable.
 * - InterleavingEquivalence: any concurrent execution of create/claim/complete equals SOME
 *   sequential ordering of the same ops — i.e. every repository op is one atomic transaction.
 * - Transition legality: the repository accepts a status action iff
 *   [WorkItemTransitionValidator] allows it; rejected actions leave the row unchanged.
 * - Cycle rejection: an edge insert whose reverse path exists is rejected and not persisted.
 */
class TaskBoardRepositoryPropertyTest {

    private class Board(val now: () -> Long = { 1_000L }) {
        val dao = FakeWorkItemDAO()
        val repository = TaskBoardRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            now = now,
        )
    }

    private val conversationId = Uuid.random()

    private fun entity(
        id: Uuid,
        subject: String,
        status: WorkItemStatus = WorkItemStatus.Pending,
        ownerHandleId: String? = null,
        leaseExpiresAt: Long? = null,
    ): WorkItemEntity = WorkItemEntity(
        id = id.toString(),
        conversationId = conversationId.toString(),
        subject = subject,
        status = status.name,
        ownerHandleId = ownerHandleId,
        ownerName = ownerHandleId,
        leaseExpiresAt = leaseExpiresAt,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun edge(blocker: Uuid, blocked: Uuid): WorkItemDependencyEntity =
        WorkItemDependencyEntity(
            conversationId = conversationId.toString(),
            blockerId = blocker.toString(),
            blockedId = blocked.toString(),
        )

    private suspend fun TaskBoardRepository.claim(id: Uuid, handle: String): BoardMutationResult =
        update(conversationId, WorkItemPatch(id = id, action = WorkItemAction.Claim), BoardActor(handle, handle))

    // --- SingleOwnerClaim ---------------------------------------------------------------------

    @Test
    fun singleOwnerClaim_concurrentClaimsYieldExactlyOneOwner(): Unit = runBlocking {
        checkAll(50, Arb.int(2..8)) { claimers ->
            val board = Board()
            val itemId = Uuid.random()
            board.dao.insert(entity(itemId, "contested"))

            val results = coroutineScope {
                (1..claimers).map { n ->
                    async(Dispatchers.Default) { "h$n" to board.repository.claim(itemId, "h$n") }
                }.awaitAll()
            }

            val accepted = results.filter { it.second is BoardMutationResult.Accepted }
            assertEquals("exactly one concurrent claim must win", 1, accepted.size)
            val snapshot = board.repository.get(conversationId, itemId)!!
            assertEquals(WorkItemStatus.InProgress, snapshot.item.status)
            assertEquals(accepted.single().first, snapshot.item.ownerHandleId)
        }
    }

    @Test
    fun singleOwnerClaim_oneOwnerMayHoldManyItems(): Unit = runBlocking {
        checkAll(50, Arb.int(2..6)) { itemCount ->
            val board = Board()
            val ids = (1..itemCount).map { n ->
                Uuid.random().also { board.dao.insert(entity(it, "item-$n")) }
            }

            ids.forEach { id ->
                val result = board.repository.claim(id, "worker")
                assertTrue("multi-claim per owner is allowed (decision #5)", result is BoardMutationResult.Accepted)
            }
            ids.forEach { id ->
                assertEquals("worker", board.repository.get(conversationId, id)!!.item.ownerHandleId)
            }
        }
    }

    @Test
    fun claim_expiredLeaseIsTakeOverable_unexpiredIsNot(): Unit = runBlocking {
        checkAll(50, Arb.int(1..1_000), Arb.int(1..1_000)) { lease, now ->
            val board = Board(now = { now.toLong() })
            val itemId = Uuid.random()
            board.dao.insert(
                entity(itemId, "leased", WorkItemStatus.InProgress, ownerHandleId = "dead", leaseExpiresAt = lease.toLong())
            )

            val result = board.repository.claim(itemId, "alive")
            val owner = board.repository.get(conversationId, itemId)!!.item.ownerHandleId
            if (lease <= now) {
                assertTrue("expired lease must be claimable (lease backstop)", result is BoardMutationResult.Accepted)
                assertEquals("alive", owner)
            } else {
                assertTrue("live claim must not be stolen", result is BoardMutationResult.Rejected)
                assertEquals("dead", owner)
            }
        }
    }

    // --- DeleteUnblocksDependents ---------------------------------------------------------------

    @Test
    fun deleteUnblocksDependents_deletingAllBlockersMakesItemClaimable(): Unit = runBlocking {
        checkAll(50, Arb.int(1..4)) { blockerCount ->
            val board = Board()
            val target = Uuid.random()
            board.dao.insert(entity(target, "target"))
            val blockers = (1..blockerCount).map { n ->
                Uuid.random().also {
                    board.dao.insert(entity(it, "blocker-$n"))
                    board.dao.insertDependency(edge(blocker = it, blocked = target))
                }
            }

            assertTrue(
                "claim must be rejected while blockers are unresolved",
                board.repository.claim(target, "w") is BoardMutationResult.Rejected
            )

            blockers.forEach { blocker ->
                val deleted = board.repository.update(
                    conversationId, WorkItemPatch(id = blocker, action = WorkItemAction.Delete)
                )
                assertTrue(deleted is BoardMutationResult.Accepted)
            }

            val claim = board.repository.claim(target, "w")
            assertTrue("deleting every blocker must unblock the dependent", claim is BoardMutationResult.Accepted)
            assertEquals(emptyList<Uuid>(), board.repository.get(conversationId, target)!!.blockedBy)
        }
    }

    // --- transition legality (repository agrees with the domain validator) ---------------------

    @Test
    fun update_acceptsStatusActionIffValidatorAllows(): Unit = runBlocking {
        val arbStatus = Arb.element(WorkItemStatus.entries)
        val arbAction = Arb.element(
            WorkItemAction.Claim, WorkItemAction.Complete, WorkItemAction.Release,
            WorkItemAction.Reopen, WorkItemAction.Delete,
        )
        checkAll(200, arbStatus, arbAction) { status, action ->
            val board = Board(now = { 1_000L })
            val itemId = Uuid.random()
            val owner = if (status == WorkItemStatus.InProgress) "holder" else null
            // Future lease: the lease backstop must not widen legality for a live claim.
            board.dao.insert(entity(itemId, "item", status, ownerHandleId = owner, leaseExpiresAt = owner?.let { 2_000L }))

            val result = board.repository.update(
                conversationId, WorkItemPatch(id = itemId, action = action), BoardActor("actor")
            )
            val expected = WorkItemTransitionValidator.transition(status, action)

            when (expected) {
                is WorkItemTransitionResult.Allowed -> {
                    assertTrue("validator allows $action on $status", result is BoardMutationResult.Accepted)
                    assertEquals(expected.to.name, board.dao.getById(itemId.toString())!!.status)
                }

                is WorkItemTransitionResult.Rejected -> {
                    assertTrue("validator rejects $action on $status", result is BoardMutationResult.Rejected)
                    assertEquals("rejected action must not change the row", status.name, board.dao.getById(itemId.toString())!!.status)
                }
            }
        }
    }

    // --- cycle rejection ------------------------------------------------------------------------

    @Test
    fun update_rejectsCycleClosingEdgeAndPersistsNothing(): Unit = runBlocking {
        checkAll(50, Arb.int(1..5)) { chainLength ->
            val board = Board()
            val chain = (0..chainLength).map { n ->
                Uuid.random().also { board.dao.insert(entity(it, "chain-$n")) }
            }
            chain.zipWithNext().forEach { (blocker, blocked) ->
                board.dao.insertDependency(edge(blocker, blocked))
            }

            // chain.first() -> ... -> chain.last(); adding "first blockedBy last" closes the loop.
            val result = board.repository.update(
                conversationId, WorkItemPatch(id = chain.first(), addBlockedBy = listOf(chain.last()))
            )

            assertTrue("cycle-closing edge must be rejected", result is BoardMutationResult.Rejected)
            assertEquals(
                "rejected edge must not be persisted",
                chainLength, board.dao.listDependencies(conversationId.toString()).size
            )
        }
    }

    // --- InterleavingEquivalence ----------------------------------------------------------------

    private sealed interface Op {
        data class Create(val subject: String) : Op
        data class Claim(val itemIndex: Int, val handle: String) : Op
        data class Complete(val itemIndex: Int) : Op
    }

    @Test
    fun interleavingEquivalence_concurrentRunEqualsSomeSequentialOrder(): Unit = runBlocking {
        val arbOp: Arb<Op> = Arb.choice(
            Arb.int(0..999).map { Op.Create("created-$it") },
            arbitrary { rs ->
                Op.Claim(Arb.int(0..2).bind(), "h${Arb.int(0..3).bind()}")
            },
            Arb.int(0..2).map { Op.Complete(it) },
        )
        checkAll(30, Arb.list(arbOp, 2..4)) { ops ->
            val seeded = (0..2).map { Uuid.random() }

            suspend fun seed(board: Board) {
                seeded.forEachIndexed { n, id -> board.dao.insert(entity(id, "seeded-$n")) }
                board.dao.insertDependency(edge(blocker = seeded[0], blocked = seeded[1]))
            }

            suspend fun run(board: Board, op: Op) {
                when (op) {
                    is Op.Create -> board.repository.create(conversationId, WorkItemDraft(subject = op.subject))
                    is Op.Claim -> board.repository.claim(seeded[op.itemIndex], op.handle)
                    is Op.Complete -> board.repository.update(
                        conversationId, WorkItemPatch(id = seeded[op.itemIndex], action = WorkItemAction.Complete)
                    )
                }
            }

            val concurrent = Board().also { seed(it) }
            coroutineScope {
                ops.map { op -> async(Dispatchers.Default) { run(concurrent, op) } }.awaitAll()
            }
            val concurrentState = fingerprint(concurrent)

            val sequentialStates = permutations(ops).map { ordering ->
                val board = Board().also { seed(it) }
                ordering.forEach { run(board, it) }
                fingerprint(board)
            }

            assertTrue(
                "concurrent state $concurrentState must equal some sequential merge",
                sequentialStates.any { it == concurrentState }
            )
        }
    }

    /** Board state keyed by subject (create assigns fresh ids, subjects are unique per run). */
    private suspend fun fingerprint(board: Board): Map<String, Triple<String, String?, Set<String>>> {
        val snapshots = board.repository.list(conversationId)
        val subjectById = snapshots.associate { it.item.id to it.item.subject }
        return snapshots.associate { snapshot ->
            snapshot.item.subject to Triple(
                snapshot.item.status.name,
                snapshot.item.ownerHandleId,
                snapshot.blockedBy.map { subjectById.getValue(it) }.toSet(),
            )
        }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.indices.flatMap { i ->
            permutations(items.subList(0, i) + items.subList(i + 1, items.size))
                .map { rest -> listOf(items[i]) + rest }
        }
    }
}
