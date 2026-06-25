package me.rerere.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ensureWorkspace must MATERIALIZE the injected default project dir, because buildShellContext
 * `require`s the resolved working directory to exist — a workspace whose (unset) working_dir resolves
 * to the default would otherwise fail to run a shell. Injected (not hardcoded) so this generic module
 * stays brand-agnostic; the app DI passes ".poci/scratch".
 */
class WorkspaceManagerDefaultProjectDirTest {

    @Test
    fun `ensureWorkspace materializes the injected default project dir`() {
        val baseDir = Files.createTempDirectory("ws-default-dir").toFile()
        val manager = WorkspaceManager(
            baseDir,
            shellRunner = HostShellRunner(),
            defaultProjectDir = ".poci/scratch",
        )

        manager.ensureWorkspace("ws1")

        assertTrue(
            "the injected default project dir must exist under the files root",
            File(manager.filesDir("ws1"), ".poci/scratch").isDirectory,
        )
    }

    @Test
    fun `ensureWorkspace creates no default dir when none is injected`() {
        val baseDir = Files.createTempDirectory("ws-no-default").toFile()
        // defaultProjectDir defaults to "" — the historical behaviour (files root only).
        val manager = WorkspaceManager(baseDir, shellRunner = HostShellRunner())

        manager.ensureWorkspace("ws1")

        assertTrue(manager.filesDir("ws1").isDirectory)
        assertFalse(File(manager.filesDir("ws1"), ".poci").exists())
    }
}
