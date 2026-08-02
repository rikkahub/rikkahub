package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class RootfsPathResolutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var manager: WorkspaceManager

    private val root = "test-workspace"

    private fun createManager(): WorkspaceManager {
        skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        return WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        ).also { it.ensureWorkspace(root) }
    }

    @Test
    fun readsFileWrittenThroughBindMountPath() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()
        File(skillsDir, "issue-1561/SKILL.md").writeText("---\nversion: before\n---\n")

        val size = manager.rootfsFileSize(root, "/skills/issue-1561/SKILL.md")
        val buffer = ByteArrayOutputStream(size.toInt())
        manager.exportRootfsFile(root, "/skills/issue-1561/SKILL.md", buffer)

        assertEquals("---\nversion: before\n---\n", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun bindMountTargetDoesNotMatchLongerSiblingPrefix() {
        val skills = tempFolder.newFolder("skills-src")
        val skillsets = tempFolder.newFolder("skillsets-src")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skills, target = "/skills"),
                WorkspaceBindMount(source = skillsets, target = "/skillsets"),
            ),
        ).also { it.ensureWorkspace(root) }

        assertEquals(skills, manager.resolveRootfsPath(root, "/skills/a.md").rootDir)
        assertEquals(skillsets, manager.resolveRootfsPath(root, "/skillsets/a.md").rootDir)
    }

    @Test
    fun workspacePathStillResolvesToFilesArea() {
        manager = createManager()
        File(manager.filesDir(root), "notes.txt").writeText("hello")

        val location = manager.resolveRootfsPath(root, "/workspace/notes.txt")
        assertEquals(manager.filesDir(root), location.rootDir)
        assertEquals("notes.txt", location.relativePath)

        val buffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/workspace/notes.txt", buffer)
        assertEquals("hello", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun normalizedTraversalResolvesToItsActualBindMount() {
        manager = createManager()

        val location = manager.resolveRootfsPath(root, "/workspace/../../skills/note.txt")
        val entry = manager.writeRootfsText(root, "/workspace/../../skills/note.txt", "hello")

        assertEquals(skillsDir, location.rootDir)
        assertEquals("note.txt", location.relativePath)
        assertEquals("/skills/note.txt", entry.path)
        assertEquals("hello", File(skillsDir, "note.txt").readText())
    }

    @Test
    fun normalizedWorkspaceWriteUsesFilesArea() {
        manager = createManager()

        val entry = manager.writeRootfsText(root, "/workspace//notes/./today.txt", "hello")

        assertEquals("/workspace/notes/today.txt", entry.path)
        assertEquals("hello", File(manager.filesDir(root), "notes/today.txt").readText())
    }

    @Test
    fun writeRejectsAbsoluteGuestSymlinkWithoutTouchingGuestTarget() {
        manager = createManager()
        val guestTarget = File(manager.filesDir(root), "hostname").apply { writeText("workspace") }
        val link = File(manager.linuxDir(root), "etc/hostname")
        link.parentFile!!.mkdirs()
        Files.createSymbolicLink(link.toPath(), Paths.get("/workspace/hostname"))

        val error = assertThrows(IllegalStateException::class.java) {
            manager.writeRootfsText(root, "/etc/hostname", "changed")
        }

        assertTrue(error.message!!.contains("Symbolic links are not supported"))
        assertTrue(error.message!!.contains("workspace_shell"))
        assertEquals("workspace", guestTarget.readText())
    }

    @Test
    fun editReadRejectsRelativeGuestSymlink() {
        manager = createManager()
        val etcDir = File(manager.linuxDir(root), "etc").apply { mkdirs() }
        File(etcDir, "real-hostname").writeText("rikkahub")
        Files.createSymbolicLink(File(etcDir, "hostname").toPath(), java.nio.file.Path.of("real-hostname"))

        val error = assertThrows(IllegalStateException::class.java) {
            manager.rootfsFileSize(root, "/etc/hostname")
        }

        assertTrue(error.message!!.contains("Symbolic links are not supported"))
        assertEquals("rikkahub", File(etcDir, "real-hostname").readText())
    }

    @Test
    fun normalizedTmpTraversalWritesToRootfsInsteadOfTmp() {
        manager = createManager()

        val entry = manager.writeRootfsText(root, "/tmp/../etc/hostname", "rikkahub\n")

        assertEquals("/etc/hostname", entry.path)
        assertEquals("rikkahub\n", File(manager.linuxDir(root), "etc/hostname").readText())
    }

    @Test
    fun workspacePrefixDoesNotMatchWorkspaceSibling() {
        manager = createManager()

        val location = manager.resolveRootfsPath(root, "/workspace2/note.txt")

        assertEquals(manager.linuxDir(root), location.rootDir)
        assertEquals("workspace2/note.txt", location.relativePath)
    }

    @Test
    fun unknownAbsolutePathFallsBackToRootfsInterior() {
        manager = createManager()
        File(manager.linuxDir(root), "etc").mkdirs()
        File(manager.linuxDir(root), "etc/hostname").writeText("rikkahub\n")

        val buffer = ByteArrayOutputStream()
        manager.exportRootfsFile(root, "/etc/hostname", buffer)
        assertEquals("rikkahub\n", buffer.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun traversalOutOfBindMountResolvesInsideRootfs() {
        manager = createManager()
        File(manager.linuxDir(root), "secret.txt").writeText("secret")

        assertEquals(6L, manager.rootfsFileSize(root, "/skills/../secret.txt"))
    }

    @Test
    fun kernelFilesystemPathIsRejectedWithHint() {
        manager = createManager()

        val error = assertThrows(IllegalStateException::class.java) {
            manager.rootfsFileSize(root, "/proc/version")
        }
        assertTrue(error.message!!.contains("workspace_shell"))
    }

    @Test
    fun missingFileReportsOriginalAbsolutePath() {
        manager = createManager()

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/missing/SKILL.md")
        }
        assertEquals("File does not exist: /skills/missing/SKILL.md", error.message)
    }

    @Test
    fun directoryPathIsNotReadableAsFile() {
        manager = createManager()
        File(skillsDir, "issue-1561").mkdirs()

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.rootfsFileSize(root, "/skills/issue-1561")
        }
        assertEquals("Path is not a file: /skills/issue-1561", error.message)
    }
}
