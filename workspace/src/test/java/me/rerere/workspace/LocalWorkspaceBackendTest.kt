package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class LocalWorkspaceBackendTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private fun createManager(shellRunner: WorkspaceShellRunner = HostShellRunner()): Pair<WorkspaceManager, File> {
        val skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = shellRunner,
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills", writable = true),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        )
        return manager to skillsDir
    }

    @Test
    fun layoutWritableRootsIncludeOnlyWritableBindMounts() {
        val (manager, _) = createManager()
        val layout = manager.layout

        assertEquals("/workspace", layout.workspaceDir)
        assertEquals(listOf("/workspace", "/tmp", "/skills"), layout.writableRoots)
        assertTrue(layout.isWritable("/workspace"))
        assertTrue(layout.isWritable("/skills/demo/SKILL.md"))
        assertFalse(layout.isWritable("/upload/a.txt"))
        assertFalse(layout.isWritable("/workspace-other/a.txt"))
        assertFalse(layout.isWritable("/"))
    }

    @Test
    fun layoutConvertsBetweenAreaAndAbsolutePaths() {
        val layout = WorkspaceLayout(workspaceDir = "/workspace", writableRoots = emptyList())

        assertEquals("/workspace", layout.absolutePath(WorkspaceStorageArea.FILES, ""))
        assertEquals("/workspace/src/a.kt", layout.absolutePath(WorkspaceStorageArea.FILES, "src/a.kt"))
        assertEquals("/", layout.absolutePath(WorkspaceStorageArea.LINUX, ""))
        assertEquals("/etc/hostname", layout.absolutePath(WorkspaceStorageArea.LINUX, "etc/hostname"))
        assertEquals("", layout.relativeToWorkspace("/workspace"))
        assertEquals("src", layout.relativeToWorkspace("/workspace/src"))
        assertEquals("src", layout.relativeToWorkspace("src"))
    }

    @Test
    fun fileWrittenByAreaPathIsReadableByAbsolutePath() {
        val (manager, skillsDir) = createManager()
        val backend = manager.backend(root)

        backend.writeText("notes/a.txt", "hello", overwrite = true)
        File(skillsDir, "demo.md").writeText("skill")

        assertEquals(5L, backend.absoluteFileSize("/workspace/notes/a.txt"))
        val workspaceFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/workspace/notes/a.txt", workspaceFile)
        assertEquals("hello", workspaceFile.toString(Charsets.UTF_8.name()))

        val skillFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/skills/demo.md", skillFile)
        assertEquals("skill", skillFile.toString(Charsets.UTF_8.name()))

        assertEquals(
            File(manager.filesDir(root), "notes/a.txt").canonicalFile,
            backend.localFile(WorkspaceStorageArea.FILES, "notes/a.txt"),
        )
    }

    @Test
    fun terminalLaunchSharesMountTableWithCommandExecution() {
        val nativeLibraryDir = tempFolder.newFolder("native")
        val (manager, skillsDir) = createManager(ProotShellRunner(nativeLibraryDir))
        manager.ensureWorkspace(root)

        val launch = manager.terminalLaunch(root, shellCompatibilityMode = true)!!

        assertEquals(File(nativeLibraryDir, "libproot_exec.so").absolutePath, launch.executable)
        assertEquals(manager.filesDir(root).absolutePath, launch.cwd)
        assertEquals(
            listOf("--root-id", "--link2symlink", "--kill-on-exit", "-r", manager.linuxDir(root).absolutePath),
            launch.args.take(5),
        )
        assertTrue(launch.args.containsAll(listOf("-w", "/workspace")))
        assertTrue("${manager.filesDir(root).absolutePath}:/workspace" in launch.args)
        assertTrue("${skillsDir.absolutePath}:/skills" in launch.args)
        assertTrue(launch.args.any { it.endsWith(":/upload") })
        // 空 Rootfs 里没有 bash, 回退到 /bin/sh
        assertEquals(listOf("USER=root", "SHELL=/bin/sh", "/bin/sh"), launch.args.takeLast(3))
        assertTrue("PROOT_NO_SECCOMP=1" in launch.env)
        assertTrue("TMPDIR=${manager.tempDir(root).absolutePath}" in launch.env)
    }

    @Test
    fun hostShellRunnerHasNoInteractiveTerminal() {
        val (manager, _) = createManager()
        assertEquals(null, manager.terminalLaunch(root, shellCompatibilityMode = false))
    }
}
