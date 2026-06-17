package me.rerere.rikkahub.data.repository.workspace

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.shellrun.RoomShellRunStore
import me.rerere.rikkahub.data.db.entity.ShellRunEntity
import me.rerere.rikkahub.data.db.entity.ShellRunStatus
import me.rerere.rikkahub.data.repository.BoardTransactionRunner
import me.rerere.rikkahub.data.repository.fakes.FakeAgentEventDAO
import me.rerere.rikkahub.data.repository.fakes.FakeShellRunDAO
import me.rerere.rikkahub.data.repository.resolveTailFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * Tail-scoping regression suite for `workspace_shell_tail` (issue #291). The taskId is
 * MODEL-controlled, so a tail read must be confined to runs the caller actually owns — the old
 * [me.rerere.rikkahub.data.repository.WorkspaceRepository.tailShellRun] ignored the workspace `id`
 * and reconstructed the file path from `shellTasksDir/<taskId>.output` WITHOUT consulting the
 * persisted row, so any taskId read any background run's output across workspaces/conversations, and
 * a stale file was still readable after the conversation's row was deleted.
 *
 * Why this seam, not the real [me.rerere.rikkahub.data.repository.WorkspaceRepository]: its ctor needs
 * `SettingsStore` (Android Context + AppScope) and the abstract Room `AppDatabase.withTransaction`,
 * and the :app unit-test classpath carries no Robolectric/mockk — the same constraint and in-repo
 * precedent as [WorkspaceCwdResolutionTest] and WorkspaceRepositoryShellEnableTest. So the scoping
 * DECISION is extracted into the pure [resolveTailFile] chokepoint, exercised here against the same
 * [FakeShellRunDAO] + [RoomShellRunStore] the repository wires; `tailShellRun` itself is the thin IO
 * wrapper (parse UUID -> getByTaskId -> resolveTailFile -> read) over exactly this decision.
 */
class WorkspaceTailScopingTest {

    private class FakeTransactions : BoardTransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> inTransaction(block: suspend () -> T): T = mutex.withLock { block() }
    }

    private val tempFiles = mutableListOf<File>()

    private fun tempOutput(contents: String): File {
        val f = File.createTempFile("shell-tail-", ".output").also { tempFiles += it }
        f.writeText(contents)
        return f
    }

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    private val wsA = "ws-A"
    private val wsB = "ws-B"
    private val convA = Uuid.random()
    private val convB = Uuid.random()

    private fun row(taskId: Uuid, workspaceId: String, conversationId: Uuid, output: File) =
        ShellRunEntity(
            taskId = taskId.toString(),
            conversationId = conversationId.toString(),
            workspaceId = workspaceId,
            command = "sleep 999",
            cwd = "",
            outputPath = output.absolutePath,
            status = ShellRunStatus.BACKGROUND_RUNNING.name,
            createdAt = 1L,
        )

    /** Two rows: taskA in ws-A/conv-A, taskB in ws-B/conv-B, each with its own real output file. */
    private fun fixture(): Triple<RoomShellRunStore, FakeShellRunDAO, Pair<Uuid, Uuid>> = runBlocking {
        val dao = FakeShellRunDAO()
        val store = RoomShellRunStore(
            dao = dao,
            agentEventDao = FakeAgentEventDAO(),
            transactions = FakeTransactions(),
            now = { 1L },
        )
        val taskA = Uuid.random()
        val taskB = Uuid.random()
        dao.insert(row(taskA, wsA, convA, tempOutput("AAA-output")))
        dao.insert(row(taskB, wsB, convB, tempOutput("BBB-output")))
        Triple(store, dao, taskA to taskB)
    }

    @Test
    fun `matching workspace and conversation resolves the run's own output file`() = runBlocking {
        val (store, _, ids) = fixture()
        val (taskA, _) = ids
        val file = resolveTailFile(store.getByTaskId(taskA), wsA, convA.toString())
        assertEquals("AAA-output", file!!.readText())
    }

    @Test
    fun `wrong workspace resolves to null`() = runBlocking {
        val (store, _, ids) = fixture()
        val (taskA, _) = ids
        // Caller in ws-B / conv-A asks for taskA (which lives in ws-A): cross-workspace read denied.
        assertNull(resolveTailFile(store.getByTaskId(taskA), wsB, convA.toString()))
    }

    @Test
    fun `wrong conversation resolves to null`() = runBlocking {
        val (store, _, ids) = fixture()
        val (taskA, _) = ids
        // Right workspace, wrong conversation: cross-conversation read denied.
        assertNull(resolveTailFile(store.getByTaskId(taskA), wsA, convB.toString()))
    }

    @Test
    fun `missing row resolves to null`() = runBlocking {
        val (store, _, _) = fixture()
        assertNull(resolveTailFile(store.getByTaskId(Uuid.random()), wsA, convA.toString()))
    }

    @Test
    fun `deleted row resolves to null even though the output file still exists on disk`() = runBlocking {
        val (store, dao, ids) = fixture()
        val (taskA, _) = ids
        // The output file still on disk, but the row is gone (conversation deleted): no stale read.
        val onDisk = File(store.getByTaskId(taskA)!!.outputPath)
        dao.deleteByConversationId(convA.toString())
        assert(onDisk.exists()) { "precondition: the output file must still be on disk after the row delete" }
        assertNull(resolveTailFile(store.getByTaskId(taskA), wsA, convA.toString()))
    }

    @Test
    fun `malformed taskId throws on parse`() {
        assertThrows(IllegalArgumentException::class.java) { Uuid.parse("not-a-uuid") }
    }
}
