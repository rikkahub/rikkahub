package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsPathTest {
    @Test
    fun normalizesEquivalentAbsoluteGuestPaths() {
        mapOf(
            "/workspace/file.txt" to "/workspace/file.txt",
            "/workspace//nested///file.txt" to "/workspace/nested/file.txt",
            "/workspace/./file.txt" to "/workspace/file.txt",
            "/workspace\\nested\\file.txt" to "/workspace/nested/file.txt",
            "/workspace/../../skills/file.txt" to "/skills/file.txt",
            "/workspace/../skills" to "/skills",
            "/tmp/../etc/hosts" to "/etc/hosts",
            "/../../etc/hosts" to "/etc/hosts",
            "/." to "/",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, RootfsPath.normalize(input))
        }
    }

    @Test
    fun writableRootsUseNormalizedPathBoundaries() {
        listOf("/workspace", "/workspace/file.txt", "/workspace//nested/file.txt", "/tmp/file.txt")
            .forEach { path ->
                assertTrue(path, isWritable(path))
            }
        listOf(
            "/workspace2/file.txt",
            "/tmp2/file.txt",
            "/workspace/../../skills/file.txt",
            "/workspace/../skills/file.txt",
            "/tmp/../etc/hosts",
            "/workspace\\..\\skills\\file.txt",
            "/.",
            "/..",
        ).forEach { path ->
            assertFalse(path, isWritable(path))
        }
    }

    @Test
    fun rootContainsEveryAbsoluteGuestPath() {
        assertTrue(RootfsPath.isWithin("/", "/"))
        assertTrue(RootfsPath.isWithin("/workspace/file.txt", "/"))
    }

    @Test
    fun rejectsBindMountAtRootfsRoot() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBindMount(source = java.io.File("."), target = "/")
        }

        assertEquals("Binding a host directory to the Rootfs root is not supported", error.message)
    }

    @Test
    fun rejectsRelativeAndInvalidPaths() {
        listOf(".", "..", "workspace/file.txt", "\u0000/workspace/file.txt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPath.normalize(path)
            }
        }
    }

    private fun isWritable(path: String): Boolean =
        RootfsPath.isWithin(path, WorkspaceManager.ROOTFS_WORKSPACE_DIR) || RootfsPath.isWithin(path, "/tmp")
}
