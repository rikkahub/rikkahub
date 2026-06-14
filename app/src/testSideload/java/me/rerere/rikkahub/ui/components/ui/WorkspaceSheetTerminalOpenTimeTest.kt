package me.rerere.rikkahub.ui.components.ui

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkahub.ui.pages.extensions.workspace.workspaceTerminalCwd
import me.rerere.workspace.WorkspaceCwdPolicy
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.coroutineContext

class WorkspaceSheetTerminalOpenTimeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val segments = listOf("project", "src", "main", ".config")

    @Test
    fun `P-TERMINAL-OPEN-TIME terminal uses working dir changed earlier in the same sheet`(): Unit = runBlocking {
        val nonBlankWorkingDir = arbitrary {
            Arb.list(Arb.element(segments), 1..4).bind().joinToString("/")
        }
        checkAll(200, nonBlankWorkingDir) { target ->
            val store = TerminalOpenTimeStore(listOf(WorkspaceSheetWorkspace("w1", "Workspace", "")))
            val controller = WorkspaceSheetController(store, CoroutineScope(coroutineContext))
            val files = temp.newFolder("files_${System.nanoTime()}")
            try {
                controller.activate(assistantWorkspaceId = "w1")
                yield()
                controller.browseTo(target)
                yield()
                controller.setCurrentAsProjectDir()
                yield()
                yield()

                val terminalOpenTimeCwd = workspaceTerminalCwd(files, store.workingDir("w1"))

                assertEquals(
                    WorkspaceCwdPolicy.toShellPath(WorkspaceCwdPolicy.normalize(target)),
                    terminalOpenTimeCwd,
                )
            } finally {
                controller.close()
            }
        }
    }
}

private class TerminalOpenTimeStore(
    rows: List<WorkspaceSheetWorkspace>,
) : WorkspaceSheetStore {
    private val rowsFlow = MutableStateFlow(rows)

    override fun workspacesFlow() = rowsFlow

    override suspend fun resolvedProjectDir(id: String): String {
        val workingDir = workingDir(id)
        return if (workingDir.isBlank()) {
            WorkspaceCwdPolicy.DEFAULT_SCRATCH.joinToString("/")
        } else {
            WorkspaceCwdPolicy.normalize(workingDir)
        }
    }

    override suspend fun listFiles(id: String, path: String): List<WorkspaceFileEntry> = emptyList()

    override suspend fun setWorkingDir(id: String, path: String): Boolean {
        val normalized = WorkspaceCwdPolicy.normalize(path)
        rowsFlow.value = rowsFlow.value.map {
            if (it.id == id) it.copy(workingDir = normalized) else it
        }
        return true
    }

    fun workingDir(id: String): String = rowsFlow.value.first { it.id == id }.workingDir
}
