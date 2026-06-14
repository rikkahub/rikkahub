package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins that [WorkspaceManager.executeCommand] routes its cwd through the ONE central policy
 * [WorkspaceCwdPolicy.resolveRelative] (issue #282, M3) instead of mapping `cwd` -> `-w` itself:
 *
 *  - the `cwd` arg is NULLABLE; `null` means ABSENT (fall back to working_dir/default), an explicit
 *    `""`/`"."` means the files root — the Absent-vs-Explicit distinction the old `String = ""`
 *    default collapsed (W-B1/W-B2/W-I5 callers);
 *  - resolution order is explicit-override > working_dir > default `.xcloudz/scratch` (W-M3);
 *  - an ABSENT cwd over an UNSET working_dir materializes the default scratch dir via
 *    [ensureDefaultScratch] so the resolved working directory actually exists (W-B1/W-D2).
 *
 * The capturing runner records the resolved [WorkspaceShellContext.cwd] (a FILES-relative path) and
 * [WorkspaceShellContext.workingDir] (the real File) the manager hands down — that resolved value is
 * exactly what [ProotShellRunner.prootCwd] then maps to `-w`, so pinning it here pins the value both
 * the runner AND the terminal derive their `-w` from.
 *
 * FAIL-BEFORE rationale: on the unfixed manager, `cwd` is non-nullable `String = ""`, so `null` does
 * not compile and the default-scratch resolution does not exist — the context.cwd is whatever string
 * the caller passed, never `.xcloudz/scratch`.
 */
class WorkspaceManagerCwdResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class CapturingRunner : WorkspaceShellRunner {
        var last: WorkspaceShellContext? = null
        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            last = context
            return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
        }
    }

    private fun manager(runner: WorkspaceShellRunner): WorkspaceManager =
        WorkspaceManager(baseDir = tmp.newFolder("ws"), shellRunner = runner)

    @Test
    fun `absent cwd over unset working_dir resolves to and materializes the scratch default`() {
        val runner = CapturingRunner()
        val mgr = manager(runner)
        mgr.ensureWorkspace("ws1")

        mgr.executeCommand(root = "ws1", command = "echo hi", cwd = null, workingDir = "")

        val ctx = runner.last!!
        assertEquals(".xcloudz/scratch", ctx.cwd)
        // The default scratch dir is materialized so the working directory actually exists.
        assertTrue("scratch dir must be created on demand", ctx.workingDir.isDirectory)
        assertTrue(ctx.workingDir.path.endsWith(File("files/.xcloudz/scratch").path))
    }

    @Test
    fun `absent cwd over a set working_dir resolves to the working_dir`() {
        val runner = CapturingRunner()
        val mgr = manager(runner)
        mgr.ensureWorkspace("ws2")
        // The working dir must exist for the manager's exists/isDirectory guard.
        File(mgr.filesDir("ws2"), "project/sub").mkdirs()

        mgr.executeCommand(root = "ws2", command = "ls", cwd = null, workingDir = "project/sub")

        assertEquals("project/sub", runner.last!!.cwd)
    }

    @Test
    fun `explicit blank cwd resolves to the files root, not the scratch default`() {
        val runner = CapturingRunner()
        val mgr = manager(runner)
        mgr.ensureWorkspace("ws3")

        // Explicit "" is the files root even when working_dir is unset (Absent-vs-Explicit).
        mgr.executeCommand(root = "ws3", command = "ls", cwd = "", workingDir = "")

        assertEquals("", runner.last!!.cwd)
        assertEquals(mgr.filesDir("ws3").canonicalPath, runner.last!!.workingDir.canonicalPath)
    }

    @Test
    fun `explicit cwd overrides a set working_dir`() {
        val runner = CapturingRunner()
        val mgr = manager(runner)
        mgr.ensureWorkspace("ws4")
        File(mgr.filesDir("ws4"), "override").mkdirs()

        mgr.executeCommand(root = "ws4", command = "ls", cwd = "override", workingDir = "ignored")

        assertEquals("override", runner.last!!.cwd)
    }
}
