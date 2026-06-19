package me.rerere.rikkahub.ui.components.ui

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.workspace.WorkspaceCwdPolicy
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

class WorkspaceSheetVMPropertyTest {
    private val segments = listOf("project", "src", "main", ".xcloudz", "scratch", ".config")

    @Test
    fun `P-WS-LAZY repo is not touched before workspace tab activation`(): Unit = runBlocking {
        checkAll(200, Arb.list(Arb.element(NonWorkspaceEvent.entries), 0..40)) { events ->
            val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = "project")))
            val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            try {
                events.forEach {
                    when (it) {
                        NonWorkspaceEvent.SelectAssistant -> controller.syncAssistantWorkspaceId("w1")
                        NonWorkspaceEvent.OpenQuickMessages -> Unit
                        NonWorkspaceEvent.OpenSkills -> Unit
                    }
                    yield()
                }
                assertEquals(0, store.callCount)
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `P-START-PATH activation starts browser at resolved project dir`(): Unit = runBlocking {
        checkAll(200, arbWorkingDir()) { workingDir ->
            val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = workingDir)))
            val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            try {
                controller.activate(assistantWorkspaceId = "w1")
                yield()
                yield()
                assertEquals(store.resolveWorkingDir("w1"), controller.state.value.path)
                assertEquals(store.resolveWorkingDir("w1"), controller.state.value.projectDir)
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `P-SET-CURRENT setting current folder writes normalized path and row emission displays it`(): Unit =
        runBlocking {
            checkAll(200, arbWorkingDir(), arbWorkingDir().filterNotBlank()) { initial, target ->
                val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = initial)))
                val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
                try {
                    controller.activate(assistantWorkspaceId = "w1")
                    yield()
                    controller.browseTo(target)
                    yield()
                    controller.setCurrentAsProjectDir()
                    yield()
                    yield()
                    assertEquals(WorkspaceCwdPolicy.normalize(target), store.workingDir("w1"))
                    assertEquals(WorkspaceCwdPolicy.normalize(target), controller.state.value.projectDir)
                } finally {
                    controller.close()
                }
            }
        }

    @Test
    fun `P-SET-IDEMPOTENT setting already-current folder does not write`(): Unit = runBlocking {
        checkAll(200, arbWorkingDir().filterNotBlank()) { current ->
            val normalized = WorkspaceCwdPolicy.normalize(current)
            val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = normalized)))
            val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            try {
                controller.activate(assistantWorkspaceId = "w1")
                yield()
                controller.setCurrentAsProjectDir()
                yield()
                assertEquals(0, store.setWorkingDirCalls)
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `P-WORKSPACE-SWAP-CLEARS selecting W2 never renders W1 files under W2`(): Unit = runBlocking {
        checkAll(200, Arb.boolean()) { _ ->
            val store = FakeWorkspaceSheetStore(
                listOf(row("w1", workingDir = "one"), row("w2", workingDir = "two")),
                filesByWorkspace = mapOf(
                    "w1" to listOf(entry("one/w1-file")),
                    "w2" to listOf(entry("two/w2-file")),
                ),
            )
            val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            try {
                controller.activate(assistantWorkspaceId = "w1")
                yield()
                yield()
                assertEquals("w1", controller.state.value.selectedWorkspaceId)

                controller.selectWorkspace("w2") {}
                assertEquals("w2", controller.state.value.selectedWorkspaceId)
                assertTrue(controller.state.value.entries.none { it.path.contains("w1-file") })

                yield()
                yield()
                assertEquals("w2", controller.state.value.selectedWorkspaceId)
                assertTrue(controller.state.value.entries.none { it.path.contains("w1-file") })
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `P-SELECT-ROUNDTRIP selecting W writes assistant id and displays W row`(): Unit = runBlocking {
        checkAll(200, Arb.element("w1", "w2")) { selected ->
            val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = "one"), row("w2", workingDir = "two")))
            val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            val writes = mutableListOf<String>()
            try {
                controller.activate(assistantWorkspaceId = null)
                yield()
                controller.selectWorkspace(selected) { writes += it }
                yield()
                yield()
                assertEquals(listOf(selected), writes)
                assertEquals(selected, controller.state.value.selectedWorkspaceId)
                assertEquals(store.resolveWorkingDir(selected), controller.state.value.projectDir)
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `P-AUTOSELECT-ONCE exactly one workspace auto-selects once for empty assistant`() = runBlocking {
        val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = "project")))
        val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
        val writes = mutableListOf<String>()
        try {
            controller.activate(assistantWorkspaceId = null) { writes += it }
            yield()
            store.emit(listOf(row("w1", name = "Renamed", workingDir = "project")))
            yield()
            assertEquals(listOf("w1"), writes)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `P-AUTOSELECT-ONCE does not auto-select zero or many workspaces`(): Unit = runBlocking {
        checkAll(100, Arb.element(0, 2, 3)) { size ->
            val rows = (1..size).map { row("w$it", workingDir = "project$it") }
            val store = FakeWorkspaceSheetStore(rows)
            val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            val writes = mutableListOf<String>()
            try {
                controller.activate(assistantWorkspaceId = null) { writes += it }
                yield()
                assertEquals(emptyList<String>(), writes)
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `P-REOPEN-PERSISTENCE reopen starts at persisted project dir`(): Unit = runBlocking {
        checkAll(150, arbWorkingDir().filterNotBlank()) { target ->
            val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = "")))
            val first = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            try {
                first.activate(assistantWorkspaceId = "w1")
                yield()
                first.browseTo(target)
                yield()
                first.setCurrentAsProjectDir()
                yield()
            } finally {
                first.close()
            }

            val reopened = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            try {
                reopened.activate(assistantWorkspaceId = "w1")
                yield()
                yield()
                assertEquals(WorkspaceCwdPolicy.normalize(target), reopened.state.value.path)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun `P-WS-LAZY deactivation stops workspace IO until re-activation`(): Unit = runBlocking {
        val store = FakeWorkspaceSheetStore(listOf(row("w1", workingDir = "project")))
        val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
        try {
            controller.activate(assistantWorkspaceId = "w1")
            yield()
            yield()
            val afterActivate = store.callCount
            assertTrue(afterActivate > 0)

            // The VM is ViewModelStore-scoped and outlives the sheet; leaving the Workspace tab /
            // dismissing the sheet must STOP the workspacesFlow collection so a later row emission
            // does no workspace IO while closed.
            controller.deactivate()
            store.emit(listOf(row("w1", name = "Renamed", workingDir = "elsewhere")))
            yield()
            yield()
            assertEquals(afterActivate, store.callCount)

            // Returning to the Workspace tab resumes IO.
            controller.activate(assistantWorkspaceId = "w1")
            yield()
            yield()
            assertTrue(store.callCount > afterActivate)
        } finally {
            controller.close()
        }
    }

    // Row-driven (foldRows) selection flip path — distinct from the direct-dropdown selectWorkspace
    // path covered by P-WORKSPACE-SWAP-CLEARS above. The flip resolves the selection from a workspace
    // row emission rather than a tap. NOTE: this asserts the post-fold state is consistent; the
    // intermediate two-emission render the fix removes is not reliably observable from a JVM unit
    // collector (StateFlow conflates synchronous updates) — the fix collapses foldRows to ONE atomic
    // emission so a cross-dispatcher Compose collector can never see the new id with the old files.
    @Test
    fun `WORKSPACE-SWAP-CLEARS a row-driven selection flip never keeps the previous workspace files`(): Unit =
        runBlocking {
            val store = FakeWorkspaceSheetStore(
                listOf(row("w1", workingDir = "one")),
                filesByWorkspace = mapOf(
                    "w1" to listOf(entry("one/w1-file")),
                    "w2" to listOf(entry("two/w2-file")),
                ),
            )
            val controller = WorkspaceSheetController(store = store, scope = CoroutineScope(coroutineContext))
            try {
                controller.activate(assistantWorkspaceId = "w1")
                yield()
                yield()
                assertEquals("w1", controller.state.value.selectedWorkspaceId)
                assertTrue(controller.state.value.entries.any { it.path.contains("w1-file") })

                // w2 absent: records the assistant preference without selecting (no atomic select).
                controller.syncAssistantWorkspaceId("w2")
                // A row emission drops w1 and adds w2, so foldRows resolves the selection to w2.
                store.emit(listOf(row("w2", workingDir = "two")))
                yield()
                assertEquals("w2", controller.state.value.selectedWorkspaceId)
                assertTrue(controller.state.value.entries.none { it.path.contains("w1-file") })
                yield()
                yield()
                assertEquals("w2", controller.state.value.selectedWorkspaceId)
                assertTrue(controller.state.value.entries.none { it.path.contains("w1-file") })
            } finally {
                controller.close()
            }
        }

    private fun arbWorkingDir(): Arb<String> = arbitrary {
        if (Arb.boolean().bind()) "" else Arb.list(Arb.element(segments), 1..4).bind().joinToString("/")
    }

    private fun Arb<String>.filterNotBlank(): Arb<String> = arbitrary {
        var value: String
        do {
            value = this@filterNotBlank.bind()
        } while (value.isBlank())
        value
    }

    private enum class NonWorkspaceEvent {
        SelectAssistant,
        OpenQuickMessages,
        OpenSkills,
    }
}

private class FakeWorkspaceSheetStore(
    rows: List<WorkspaceSheetWorkspace>,
    private val filesByWorkspace: Map<String, List<WorkspaceFileEntry>> = emptyMap(),
) : WorkspaceSheetStore {
    private val rowsFlow = MutableStateFlow(rows)
    var flowCollections = 0
        private set
    var resolvedProjectDirCalls = 0
        private set
    var listFilesCalls = 0
        private set
    var setWorkingDirCalls = 0
        private set

    val callCount: Int
        get() = flowCollections + resolvedProjectDirCalls + listFilesCalls + setWorkingDirCalls

    override fun workspacesFlow() = rowsFlow.also { flowCollections++ }

    override suspend fun resolvedProjectDir(id: String): String {
        resolvedProjectDirCalls++
        return resolveWorkingDir(id)
    }

    override suspend fun listFiles(id: String, path: String): List<WorkspaceFileEntry> {
        listFilesCalls++
        return filesByWorkspace[id].orEmpty()
    }

    override suspend fun setWorkingDir(id: String, path: String): Boolean {
        setWorkingDirCalls++
        val normalized = WorkspaceCwdPolicy.normalize(path)
        rowsFlow.value = rowsFlow.value.map {
            if (it.id == id) it.copy(workingDir = normalized) else it
        }
        return true
    }

    fun emit(rows: List<WorkspaceSheetWorkspace>) {
        rowsFlow.value = rows
    }

    fun workingDir(id: String): String = rowsFlow.value.first { it.id == id }.workingDir

    // Mirrors production seededRelativeCwd/resolvedWorkingDir: blank => the files root (the unified
    // project working directory default), else the normalized seed.
    fun resolveWorkingDir(id: String): String {
        val workingDir = workingDir(id)
        return if (workingDir.isBlank()) "" else WorkspaceCwdPolicy.normalize(workingDir)
    }
}

private fun row(
    id: String,
    name: String = id,
    workingDir: String,
): WorkspaceSheetWorkspace = WorkspaceSheetWorkspace(
    id = id,
    name = name,
    workingDir = workingDir,
)

private fun entry(path: String): WorkspaceFileEntry = WorkspaceFileEntry(
    path = path,
    name = path.substringAfterLast('/'),
    isDirectory = false,
    sizeBytes = 0,
    updatedAt = 0,
)
