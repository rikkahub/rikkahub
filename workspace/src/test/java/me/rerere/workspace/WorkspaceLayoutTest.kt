package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceLayoutTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deeperApprovalMountOverridesWritableParent() {
        val manager = WorkspaceManager(baseDir = tempFolder.newFolder("workspaces"))
        val layout = manager.layout(
            listOf(
                WorkspaceBindMount(File("/storage/emulated/0"), "/storage/emulated/0", writable = true),
                WorkspaceBindMount(File("/storage/emulated/0/Documents"), "/storage/emulated/0/Documents"),
                WorkspaceBindMount(
                    File("/storage/emulated/0/Documents/w"),
                    "/storage/emulated/0/Documents/w",
                    writable = true
                ),
            )
        )

        assertTrue(layout.isWritable("/storage/emulated/0/Download/a.txt"))
        assertFalse(layout.isWritable("/storage/emulated/0/Documents"))
        assertFalse(layout.isWritable("/storage/emulated/0/Documents/a.txt"))
        assertTrue(layout.isWritable("/storage/emulated/0/Documents/w/a.txt"))
        // 前缀相同但不是子目录
        assertTrue(layout.isWritable("/storage/emulated/0/Documents2/a.txt"))
    }

    @Test
    fun dotSegmentsCannotEscapeWritableRoots() {
        val layout = WorkspaceLayout(
            workspaceDir = "/workspace",
            writableRoots = listOf("/workspace", "/storage/emulated/0"),
            approvalRoots = listOf("/storage/emulated/0/Documents"),
        )

        assertTrue(layout.isWritable("/workspace/./a/../b.txt"))
        assertFalse(layout.isWritable("/workspace/../etc/passwd"))
        assertFalse(layout.isWritable("/storage/emulated/0/x/../Documents/a.txt"))
        assertFalse(layout.isWritable("/../workspace/a.txt"))
    }

    @Test
    fun commandsFailWhenWorkspaceMountIsUnavailable() {
        val manager = WorkspaceManager(baseDir = tempFolder.newFolder("workspaces"))
        manager.ensureWorkspace("ws")
        val missing = listOf(
            WorkspaceBindMount(File(tempFolder.root, "missing"), "/storage/emulated/0/missing"),
        )

        val error = assertThrows(WorkspaceMountUnavailableException::class.java) {
            manager.executeCommand("ws", "true", extraMounts = missing)
        }
        assertEquals(listOf("/storage/emulated/0/missing"), error.targets)
        assertTrue(error.message!!.contains("/storage/emulated/0/missing"))
        assertThrows(WorkspaceMountUnavailableException::class.java) {
            manager.terminalLaunch("ws", shellCompatibilityMode = false, extraMounts = missing)
        }
    }
}
