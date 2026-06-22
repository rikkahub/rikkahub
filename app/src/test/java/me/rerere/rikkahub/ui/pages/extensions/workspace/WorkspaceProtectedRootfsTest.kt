package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProtectedRootfsTest {

    private fun entry(path: String, name: String = path.substringAfterLast('/'), isDir: Boolean = true) =
        WorkspaceFileEntry(path = path, name = name, isDirectory = isDir, sizeBytes = 0, updatedAt = 0)

    @Test
    fun `top-level system dirs in the rootfs are protected`() {
        for (dir in listOf("etc", "boot", "dev", "sbin", "sys", "usr", "var", "bin", "lib", "proc", "root", "run")) {
            assertTrue(dir, isProtectedRootfsEntry(WorkspaceStorageArea.LINUX, entry(dir)))
        }
    }

    @Test
    fun `a leading slash on the path is tolerated`() {
        assertTrue(isProtectedRootfsEntry(WorkspaceStorageArea.LINUX, entry("/etc", name = "etc")))
    }

    @Test
    fun `paths that NORMALIZE to a top-level system dir are protected (no guard bypass)`() {
        // The delete sink resolves \, . and .. before deleting, so the guard must too — otherwise these
        // spellings would slip past the guard while the sink still removes the real top-level dir.
        for (p in listOf("./etc", "foo/../etc", "etc\\", "usr/../../etc", "boot/")) {
            assertTrue(p, isProtectedRootfsEntry(WorkspaceStorageArea.LINUX, entry(p, name = "etc")))
        }
    }

    @Test
    fun `nested dirs inside a system dir are NOT protected`() {
        assertFalse(isProtectedRootfsEntry(WorkspaceStorageArea.LINUX, entry("etc/cron.d", name = "cron.d")))
    }

    @Test
    fun `a file (not a directory) named like a system dir is NOT protected`() {
        assertFalse(isProtectedRootfsEntry(WorkspaceStorageArea.LINUX, entry("etc", isDir = false)))
    }

    @Test
    fun `a non-system top-level dir is NOT protected`() {
        assertFalse(isProtectedRootfsEntry(WorkspaceStorageArea.LINUX, entry("myproject")))
    }

    @Test
    fun `the FILES project area is never protected`() {
        assertFalse(isProtectedRootfsEntry(WorkspaceStorageArea.FILES, entry("etc")))
    }

    @Test
    fun `an empty path is not protected`() {
        assertFalse(isProtectedRootfsEntry(WorkspaceStorageArea.LINUX, entry("", name = "")))
    }
}
