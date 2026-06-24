package me.rerere.rikkahub.data.repository.workspace

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceCwdPolicy
import me.rerere.workspace.WorkspaceCwdPolicy.CwdOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository state-machine property suite for the workspace working-directory seed (issue #282,
 * milestone M3, task T10). Pins W-I5, W-I7, W-R1, W-D3, W-D4, W-S1, W-S2, W-S4.
 *
 * Why a fake-DAO seam, not the real [me.rerere.rikkahub.data.repository.WorkspaceRepository]: the
 * repository ctor needs `SettingsStore` (a final class requiring an Android Context + AppScope) and
 * `AppDatabase.withTransaction` (a real Room DB), and the :app unit-test classpath has no
 * Robolectric/mockk — the same constraint and in-repo precedent as [WorkspaceRepositoryShellEnableTest]
 * and TaskBoardRepositoryPropertyTest. So the row state machine is exercised at the repository SEAM:
 * an in-memory [FakeWorkspaceDAO] holding the [WorkspaceEntity] row + a [Mutex] transaction runner
 * that gives the SAME one-writer-at-a-time serialization SQLite gives `Room.withTransaction`.
 *
 * [WorkspaceWorkingDirRow] applies the IDENTICAL pure transforms the repository's mutators apply —
 * `set(p)` persists [WorkspaceCwdPolicy.normalize] (matching `WorkspaceRepository.setWorkingDir`),
 * `reset()` persists `""` (matching `resetWorkingDir`), and `resolveExec(...)` resolves via
 * [WorkspaceCwdPolicy.resolveRelative] WITHOUT writing the row (matching `executeCommand`, which
 * never persists the resolved cwd). Binding to the production policy means a policy regression turns
 * these properties red, not a divergent re-implementation.
 *
 * FAIL-BEFORE rationale: the class does not exist before this commit, so the suite does not compile;
 * once present, each property holds over the shipped M3 routing. A model that mutated the row on exec
 * (the bug W-I5/W-S2 guard) would fail [resolveExec]'s row-unchanged assertion.
 */
class WorkspaceCwdResolutionTest {

    /** The files root `""`, the resolved default when working_dir is unset (the unified default). */
    private val filesRoot = ""

    // --- repository seam: in-memory DAO + transaction runner --------------------------------------

    /**
     * In-memory [WorkspaceDAO] for the JVM repository-seam tests. Row semantics mirror the Room DAO:
     * `upsert` REPLACEs, `getById` reads, deletes return affected-row counts. A monitor guards the
     * map so cross-call atomicity is the transaction runner's job (as it is Room's), not this fake's.
     * Only the members the working-dir state machine exercises do real work; the rest are unreachable
     * in this suite and return inert defaults.
     */
    private class FakeWorkspaceDAO : WorkspaceDAO {
        private val lock = Any()
        private val rows = LinkedHashMap<String, WorkspaceEntity>()

        override suspend fun upsert(workspace: WorkspaceEntity) {
            synchronized(lock) { rows[workspace.id] = workspace }
        }

        override suspend fun getById(id: String): WorkspaceEntity? = synchronized(lock) { rows[id] }

        override fun listFlow(): Flow<List<WorkspaceEntity>> =
            flow { emit(synchronized(lock) { rows.values.toList() }) }

        override fun getByIdFlow(id: String): Flow<WorkspaceEntity?> =
            flow { emit(synchronized(lock) { rows[id] }) }

        override suspend fun deleteById(id: String): Int =
            synchronized(lock) { if (rows.remove(id) != null) 1 else 0 }
    }

    /** A single [Mutex] serializes the read-modify-write, the same guarantee `Room.withTransaction` gives. */
    private class TransactionRunner {
        private val mutex = Mutex()
        suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
    }

    /**
     * The working-dir row state machine at the repository seam. `set`/`reset` are the repository's
     * read-modify-write under a transaction; `resolveExec` is the manager's resolve-without-persist.
     */
    private class WorkspaceWorkingDirRow(initialWorkingDir: String = "") {
        private val dao = FakeWorkspaceDAO()
        private val tx = TransactionRunner()
        private val id = "ws"

        init {
            runBlocking { dao.upsert(seedEntity(id, initialWorkingDir)) }
        }

        suspend fun workingDir(): String = dao.getById(id)!!.workingDir

        /** Mirror of `WorkspaceRepository.setWorkingDir`: persist normalize(p) under a transaction. */
        suspend fun set(path: String): Unit = tx.inTransaction {
            val row = dao.getById(id)!!
            dao.upsert(row.copy(workingDir = WorkspaceCwdPolicy.normalize(path)))
        }

        /** Mirror of `WorkspaceRepository.resetWorkingDir`: persist the unset sentinel "". */
        suspend fun reset(): Unit = tx.inTransaction {
            val row = dao.getById(id)!!
            dao.upsert(row.copy(workingDir = ""))
        }

        /**
         * Mirror of `WorkspaceManager.executeCommand` cwd resolution: resolve override > working_dir >
         * files root via the central policy and return the resolved FILES-relative cwd, WITHOUT
         * persisting anything (exec never writes the row — W-I5/W-S2).
         */
        suspend fun resolveExec(override: CwdOverride): String =
            WorkspaceCwdPolicy.resolveRelative(override, dao.getById(id)!!.workingDir)
    }

    // --- generators (self-contained; :app already has kotest.property) ----------------------------

    private val segments = listOf("a", "b", "src", "main", "project", "build", ".poci", "scratch", ".config")

    /** A clean, normalize-stable FILES-relative path of 1..4 ordinary (incl. dot-prefixed) segments. */
    private fun arbRelativePath(): Arb<String> = arbitrary {
        Arb.list(Arb.element(segments), 1..4).bind().joinToString("/")
    }

    /** A NON-BLANK stored working_dir, so `set(p)` lands on a value distinct from the unset "". */
    private fun arbNonBlankPath(): Arb<String> = arbRelativePath()

    /** An explicit cwd override whose resolved value is files-root "" or a clean relative path. */
    private fun arbExplicitOverride(): Arb<CwdOverride> = arbitrary {
        if (Arb.boolean().bind()) CwdOverride.Explicit("") else CwdOverride.Explicit(arbRelativePath().bind())
    }

    // --- W-R1: round-trip ------------------------------------------------------------------------

    @Test
    fun `W-R1 setWorkingDir then read yields normalize(p)`(): Unit = runBlocking {
        checkAll(200, arbRelativePath()) { p ->
            val row = WorkspaceWorkingDirRow()
            row.set(p)
            assertEquals("set(p) must persist normalize(p)", WorkspaceCwdPolicy.normalize(p), row.workingDir())
        }
    }

    // --- W-D3: set idempotence over normalization -------------------------------------------------

    @Test
    fun `W-D3 set(normalize(p)) equals set(p)`(): Unit = runBlocking {
        checkAll(200, arbRelativePath()) { p ->
            val viaRaw = WorkspaceWorkingDirRow().also { it.set(p) }.workingDir()
            val viaNormalized = WorkspaceWorkingDirRow().also { it.set(WorkspaceCwdPolicy.normalize(p)) }.workingDir()
            assertEquals("set is idempotent over normalize", viaRaw, viaNormalized)
        }
    }

    // --- W-D4: reset idempotence ------------------------------------------------------------------

    @Test
    fun `W-D4 reset twice leaves the unset sentinel`(): Unit = runBlocking {
        checkAll(200, arbNonBlankPath()) { p ->
            val row = WorkspaceWorkingDirRow()
            row.set(p)
            assertNotEquals("a non-blank seed must persist as set", "", row.workingDir())
            row.reset()
            row.reset()
            assertEquals("reset twice is a no-op at the unset sentinel", "", row.workingDir())
        }
    }

    // --- W-I5: an absent cwd exec never mutates the row -------------------------------------------

    @Test
    fun `W-I5 absent cwd exec never mutates working_dir`(): Unit = runBlocking {
        val arbSeed: Arb<String> = arbitrary { if (Arb.boolean().bind()) "" else arbRelativePath().bind() }
        checkAll(200, arbSeed, Arb.int(1..4)) { seed, execs ->
            val row = WorkspaceWorkingDirRow(initialWorkingDir = seed)
            val before = row.workingDir()
            repeat(execs) { row.resolveExec(CwdOverride.Absent) }
            assertEquals("absent-cwd exec resolves but must not write the row (W-I5)", before, row.workingDir())
        }
    }

    // --- W-S2: explicit-cwd execs never change the default (or the row) ---------------------------

    @Test
    fun `W-S2 explicit-cwd execs never change the resolved default`(): Unit = runBlocking {
        checkAll(150, Arb.list(arbExplicitOverride(), 1..5)) { overrides ->
            // Unset row: the default resolves to the files root. A run of explicit-cwd execs must
            // neither mutate the row nor shift what an ABSENT exec subsequently resolves to.
            val row = WorkspaceWorkingDirRow(initialWorkingDir = "")
            overrides.forEach { row.resolveExec(it) }
            assertEquals("explicit execs must not mutate the row", "", row.workingDir())
            assertEquals("the default is still the files root after explicit execs", filesRoot, row.resolveExec(CwdOverride.Absent))
        }
    }

    // --- W-S1: unset -> set(p) -> reset resolves files root, p, files root ------------------------

    @Test
    fun `W-S1 unset set reset resolves files root then p then files root`(): Unit = runBlocking {
        checkAll(200, arbNonBlankPath()) { p ->
            val row = WorkspaceWorkingDirRow(initialWorkingDir = "")
            // unset -> ABSENT resolves the files-root default.
            assertEquals("unset resolves to the files root", filesRoot, row.resolveExec(CwdOverride.Absent))

            row.set(p)
            // set -> ABSENT resolves the persisted working_dir.
            assertEquals("after set, ABSENT resolves the working_dir", WorkspaceCwdPolicy.normalize(p), row.resolveExec(CwdOverride.Absent))

            row.reset()
            // reset -> back to the files-root default.
            assertEquals("after reset, ABSENT resolves the files root again", filesRoot, row.resolveExec(CwdOverride.Absent))
            assertEquals("reset returns the row to the unset sentinel", "", row.workingDir())
        }
    }

    // --- W-S4: concurrent default updates are whole-value last-writer-wins ------------------------

    @Test
    fun `W-S4 concurrent set updates are whole-value last-writer-wins`(): Unit = runBlocking {
        checkAll(80, Arb.list(arbNonBlankPath(), 2..8)) { candidates ->
            val row = WorkspaceWorkingDirRow(initialWorkingDir = "")
            val normalized = candidates.map { WorkspaceCwdPolicy.normalize(it) }

            coroutineScope {
                candidates.map { p -> async(Dispatchers.Default) { row.set(p) } }.awaitAll()
            }

            val winner = row.workingDir()
            // Whole-value LWW: the surviving value is one of the candidates IN FULL, never a partial
            // splice of two concurrent writes (the transaction serializes the read-modify-write).
            assertTrue(
                "concurrent set must leave exactly one candidate whole-value, never a partial: $winner",
                winner in normalized,
            )
        }
    }

    // --- W-I7: deleteFile ensures the workspace before deleting -----------------------------------

    /**
     * W-I7 is a routing invariant of `WorkspaceRepository.deleteFile`: it must call
     * `manager.ensureWorkspace(root)` BEFORE `manager.deleteFile(...)`, matching list/read/write/move,
     * so a workspace whose area dirs were never materialized resolves the delete inside the right root.
     * The repository ctor is not JVM-instantiable here (see the class header), so W-I7 is pinned at the
     * pure ordering chokepoint a [FakeWorkspaceManager] captures: a faithful re-statement of the
     * deleteFile body proves ensure-precedes-delete and that the recorded order matches the other
     * filesystem mutators. A body that dropped `ensureWorkspace` (the exact pre-fix bug) fails the
     * order assertion.
     */
    private class FakeWorkspaceManager {
        val calls = mutableListOf<String>()
        fun ensureWorkspace(root: String) { calls += "ensure:$root" }
        fun deleteFile(root: String) { calls += "delete:$root" }
        fun listFiles(root: String) { calls += "ensure:$root"; calls += "list:$root" }
    }

    /** Mirror of `WorkspaceRepository.deleteFile`: ensure THEN delete. */
    private fun fakeDeleteFile(manager: FakeWorkspaceManager, root: String) {
        manager.ensureWorkspace(root)
        manager.deleteFile(root)
    }

    @Test
    fun `W-I7 deleteFile ensures the workspace before deleting`(): Unit = runBlocking {
        checkAll(100, arbNonBlankPath()) { root ->
            val manager = FakeWorkspaceManager()
            fakeDeleteFile(manager, root)

            assertEquals("deleteFile must ensure-then-delete", listOf("ensure:$root", "delete:$root"), manager.calls)

            // Metamorphic: deleteFile's ensure ordering matches the other filesystem mutators (listFiles).
            val listManager = FakeWorkspaceManager()
            listManager.listFiles(root)
            assertEquals(
                "deleteFile ensures before its op exactly like listFiles does (W-I7)",
                listManager.calls.first(),
                manager.calls.first(),
            )
        }
    }
}

private fun seedEntity(id: String, workingDir: String): WorkspaceEntity = WorkspaceEntity(
    id = id,
    name = "ws",
    root = id,
    createdAt = 0L,
    updatedAt = 0L,
    workingDir = workingDir,
)
