package me.rerere.rikkahub.data.repository

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.board.WorkItemStatus
import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import me.rerere.rikkahub.data.db.entity.WorkItemDependencyEntity
import me.rerere.rikkahub.data.db.entity.WorkItemEntity
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskRunDAO
import me.rerere.rikkahub.data.repository.fakes.FakeWorkItemDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Property suite for the retention sweep (SPEC.md M6 / T14): the pure windowing decision
 * [selectExpiredForRetention] plus the two repository sweeps built on it
 * ([TaskRunRepository.sweepRetention], [TaskBoardRepository.sweepRetention]), pinned as
 * PROPERTIES rather than example cases:
 *
 *  1. **Idempotence.** `sweep(sweep(db)) == sweep(db)` — the second sweep deletes 0 rows.
 *  2. **Never-delete-non-terminal.** Active/Interrupted runs and Pending/InProgress items are
 *     never swept, no matter how old.
 *  3. **Boundary exactness.** A row with `updatedAt == now - maxAge` is KEPT (delete uses
 *     `< cutoff`), and with keepNewest = N the N newest old rows survive while the (N+1)-th is
 *     swept — pinned at the production default N = 200 (item #200 kept, #201 swept).
 *  4. **No-dangle.** No dependency edge survives a board sweep with a missing endpoint.
 *  5. **Conservation.** Every ineligible row is byte-identical after the sweep.
 *  6. **Metamorphic insert-commutativity.** Inserting ineligible rows before the sweep changes
 *     neither the swept id-set nor the inserted rows: `sweep(db + ineligible)` deletes exactly
 *     `sweep(db)`'s victims.
 *
 * All repository properties run against the JVM DAO fakes (CI runs no instrumented tests); the
 * fake transaction runner serializes like `Room.withTransaction`, so the invariants pinned here
 * are the repository's own logic, not SQLite's.
 */
class RetentionPolicyPropertyTest {

    private val maxAge = 30L * 24 * 60 * 60 * 1000
    private val now = 100L * maxAge
    private val cutoff = now - maxAge

    // --- generators -------------------------------------------------------------------------

    /** One abstract row the pure function windows over. */
    private data class Row(val id: String, val conversation: String, val updatedAt: Long)

    /** updatedAt spread across the interesting regions: far-old, just-old, exact cutoff, recent. */
    private val arbUpdatedAt: Arb<Long> = Arb.element(
        listOf(
            Arb.long(0L until cutoff),            // strictly older than the cutoff
            Arb.long(cutoff..cutoff),             // exactly on the boundary
            Arb.long((cutoff + 1)..now),          // within the recency window
        )
    ).flatMap { it }

    private fun arbRows(maxConversations: Int = 3): Arb<List<Row>> =
        Arb.list(
            Arb.int(0 until maxConversations).flatMap { conv ->
                arbUpdatedAt.map { at -> Row(Uuid.random().toString(), "conv-$conv", at) }
            },
            0..40,
        )

    private fun expired(rows: List<Row>, keepNewest: Int): List<String> =
        selectExpiredForRetention(
            rows = rows,
            now = now,
            maxAgeMillis = maxAge,
            keepNewestPerConversation = keepNewest,
            conversationOf = { it.conversation },
            updatedAtOf = { it.updatedAt },
            idOf = { it.id },
        )

    // --- pure windowing properties ----------------------------------------------------------

    @Test
    fun `pure - a row is swept IFF it is BOTH strictly older than the cutoff AND beyond newest-N`() {
        runBlocking {
            checkAll(300, arbRows(), Arb.int(0..5)) { rows, keepNewest ->
                val swept = expired(rows, keepNewest).toSet()
                rows.groupBy { it.conversation }.forEach { (_, perConversation) ->
                    val newestN = perConversation.sortedByDescending { it.updatedAt }
                        .take(keepNewest).map { it.id }.toSet()
                    perConversation.forEach { row ->
                        val eligible = row.updatedAt < cutoff && row.id !in newestN
                        assertEquals(
                            "row ${row.id} (updatedAt=${row.updatedAt}, cutoff=$cutoff, newestN=${row.id in newestN})",
                            eligible,
                            row.id in swept,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `pure - the exact-cutoff row is always kept - delete uses strictly-less-than`() {
        runBlocking {
            checkAll(200, arbRows(), Arb.int(0..5)) { rows, keepNewest ->
                val boundary = Row(Uuid.random().toString(), "conv-0", cutoff)
                val swept = expired(rows + boundary, keepNewest)
                assertTrue(
                    "a row at exactly now - maxAge must never be swept",
                    boundary.id !in swept,
                )
            }
        }
    }

    @Test
    fun `pure - idempotence - sweeping the survivors sweeps nothing`() {
        runBlocking {
            checkAll(300, arbRows(), Arb.int(0..5)) { rows, keepNewest ->
                val firstSwept = expired(rows, keepNewest).toSet()
                val survivors = rows.filter { it.id !in firstSwept }
                assertEquals(
                    "the second sweep over the survivors must delete 0 rows",
                    emptyList<String>(),
                    expired(survivors, keepNewest),
                )
            }
        }
    }

    @Test
    fun `pure - at the production default the 200th-newest old row survives and the 201st is swept`() {
        // 201 old rows in one conversation, strictly ordered by recency: with keepNewest = 200 the
        // single sweepable row is precisely the oldest one (the 201st-newest).
        val rows = (1..201).map { i ->
            Row(id = "row-$i", conversation = "conv", updatedAt = cutoff - i)
        }
        val swept = expired(rows, TaskBoardRepository.DEFAULT_RETENTION_KEEP_NEWEST)
        assertEquals(listOf("row-201"), swept)
    }

    // --- task-run repository properties -----------------------------------------------------

    private class RunFixture {
        val dao = FakeTaskRunDAO()
        val repository = TaskRunRepository(dao = dao, transactions = FakeBoardTransactions(), now = { 0L })
    }

    private val arbRunTag: Arb<TaskRunStateTag> = Arb.element(TaskRunStateTag.entries)

    private data class RunSpec(val conversation: Int, val tag: TaskRunStateTag, val updatedAt: Long)

    private val arbRunSpecs: Arb<List<RunSpec>> = Arb.list(
        Arb.int(0..2).flatMap { conv ->
            arbRunTag.flatMap { tag -> arbUpdatedAt.map { at -> RunSpec(conv, tag, at) } }
        },
        0..30,
    )

    private suspend fun seedRun(f: RunFixture, spec: RunSpec): TaskRunEntity {
        val entity = TaskRunEntity(
            id = Uuid.random().toString(),
            conversationId = "conv-${spec.conversation}",
            parentToolCallId = "call",
            agentTypeId = "agent",
            prompt = "p",
            latestState = spec.tag.name,
            createdAt = spec.updatedAt,
            updatedAt = spec.updatedAt,
        )
        f.dao.upsert(entity)
        return entity
    }

    @Test
    fun `runs - non-terminal rows are conserved byte-identical and never swept`() {
        runBlocking {
            checkAll(150, arbRunSpecs, Arb.int(0..3)) { specs, keepNewest ->
                val f = RunFixture()
                val seeded = specs.map { seedRun(f, it) }
                f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)

                seeded.filter { !TaskRunStateTag.valueOf(it.latestState).isTerminal }.forEach { row ->
                    assertEquals(
                        "a non-terminal run must survive the sweep unchanged: ${row.latestState}",
                        row,
                        f.dao.getById(row.id),
                    )
                }
            }
        }
    }

    @Test
    fun `runs - sweep is idempotent - the second sweep deletes 0 rows`() {
        runBlocking {
            checkAll(150, arbRunSpecs, Arb.int(0..3)) { specs, keepNewest ->
                val f = RunFixture()
                specs.forEach { seedRun(f, it) }
                f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)
                val secondSwept =
                    f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)
                assertEquals("sweep(sweep(db)) must delete nothing", 0, secondSwept)
            }
        }
    }

    @Test
    fun `runs - metamorphic - inserting ineligible rows does not change what the sweep deletes`() {
        runBlocking {
            checkAll(100, arbRunSpecs, Arb.int(0..3)) { specs, keepNewest ->
                // Baseline: sweep the generated population alone.
                val base = RunFixture()
                val baseRows = specs.map { seedRun(base, it) }
                base.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)
                val baseSweptIds = baseRows.map { it.id }.filter { base.dao.getById(it) == null }.toSet()

                // Same population PLUS ineligible rows (non-terminal AND recent — doubly safe in
                // every conversation, so they cannot consume a newest-N slot a terminal row needs).
                val withExtra = RunFixture()
                val sameRows = baseRows.map { it.also { row -> withExtra.dao.upsert(row) } }
                val extras = (0..2).map { conv ->
                    seedRun(withExtra, RunSpec(conversation = conv, tag = TaskRunStateTag.RUNNING, updatedAt = now))
                }
                withExtra.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)

                val sweptIds = sameRows.map { it.id }.filter { withExtra.dao.getById(it) == null }.toSet()
                assertEquals("sweep must commute with inserting ineligible rows", baseSweptIds, sweptIds)
                extras.forEach { assertEquals("an inserted ineligible row is conserved", it, withExtra.dao.getById(it.id)) }
            }
        }
    }

    // --- board repository properties --------------------------------------------------------

    private class BoardFixture {
        val dao = FakeWorkItemDAO()
        val repository = TaskBoardRepository(dao = dao, transactions = FakeBoardTransactions(), now = { 0L })
    }

    private val arbItemStatus: Arb<WorkItemStatus> = Arb.element(WorkItemStatus.entries)

    private data class ItemSpec(val conversation: Int, val status: WorkItemStatus, val updatedAt: Long)

    private val arbItemSpecs: Arb<List<ItemSpec>> = Arb.list(
        Arb.int(0..2).flatMap { conv ->
            arbItemStatus.flatMap { status -> arbUpdatedAt.map { at -> ItemSpec(conv, status, at) } }
        },
        0..30,
    )

    private suspend fun seedItem(f: BoardFixture, spec: ItemSpec): WorkItemEntity {
        val entity = WorkItemEntity(
            id = Uuid.random().toString(),
            conversationId = "conv-${spec.conversation}",
            subject = "s",
            status = spec.status.name,
            createdAt = spec.updatedAt,
            updatedAt = spec.updatedAt,
        )
        f.dao.insert(entity)
        return entity
    }

    private fun WorkItemEntity.isRetained(): Boolean =
        status == WorkItemStatus.Completed.name || status == WorkItemStatus.Deleted.name

    @Test
    fun `board - open items are conserved byte-identical and never swept`() {
        runBlocking {
            checkAll(150, arbItemSpecs, Arb.int(0..3)) { specs, keepNewest ->
                val f = BoardFixture()
                val seeded = specs.map { seedItem(f, it) }
                f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)

                seeded.filter { !it.isRetained() }.forEach { row ->
                    assertEquals(
                        "an open (Pending/InProgress) item must survive the sweep unchanged",
                        row,
                        f.dao.getById(row.id),
                    )
                }
            }
        }
    }

    @Test
    fun `board - sweep is idempotent - the second sweep deletes 0 items`() {
        runBlocking {
            checkAll(150, arbItemSpecs, Arb.int(0..3)) { specs, keepNewest ->
                val f = BoardFixture()
                specs.forEach { seedItem(f, it) }
                f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)
                val secondSwept =
                    f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)
                assertEquals("board sweep(sweep(db)) must delete nothing", 0, secondSwept)
            }
        }
    }

    @Test
    fun `board - no dependency edge dangles after a sweep`() {
        runBlocking {
            checkAll(100, arbItemSpecs, Arb.int(0..2)) { specs, keepNewest ->
                val f = BoardFixture()
                val seeded = specs.map { seedItem(f, it) }
                // Chain edges within each conversation (i blocks i+1) — enough structure to force
                // edges that touch swept, kept, and mixed endpoint pairs, never a cycle.
                seeded.groupBy { it.conversationId }.forEach { (conversationId, perConversation) ->
                    perConversation.zipWithNext().forEach { (blocker, blocked) ->
                        f.dao.insertDependency(
                            WorkItemDependencyEntity(
                                conversationId = conversationId,
                                blockerId = blocker.id,
                                blockedId = blocked.id,
                            )
                        )
                    }
                }

                f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = keepNewest)

                listOf("conv-0", "conv-1", "conv-2").forEach { conversationId ->
                    f.dao.listDependencies(conversationId).forEach { edge ->
                        assertNotNull(
                            "edge blocker ${edge.blockerId} must still exist post-sweep",
                            f.dao.getById(edge.blockerId),
                        )
                        assertNotNull(
                            "edge blocked ${edge.blockedId} must still exist post-sweep",
                            f.dao.getById(edge.blockedId),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `board - a swept item takes every edge touching it along`() = runBlocking {
        val f = BoardFixture()
        val conversation = "conv-0"
        val old = seedItem(f, ItemSpec(0, WorkItemStatus.Completed, updatedAt = 0L))
        val open = seedItem(f, ItemSpec(0, WorkItemStatus.Pending, updatedAt = 0L))
        f.dao.insertDependency(WorkItemDependencyEntity(conversation, blockerId = old.id, blockedId = open.id))

        val swept = f.repository.sweepRetention(now = now, maxAgeMillis = maxAge, keepNewestPerConversation = 0)

        assertEquals(1, swept)
        assertNull("the old completed item is swept", f.dao.getById(old.id))
        assertNotNull("the open dependent survives", f.dao.getById(open.id))
        assertEquals(
            "the edge touching the swept item must be deleted with it",
            emptyList<WorkItemDependencyEntity>(),
            f.dao.listDependencies(conversation),
        )
    }
}
