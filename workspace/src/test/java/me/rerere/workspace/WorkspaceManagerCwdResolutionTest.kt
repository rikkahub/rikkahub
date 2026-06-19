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
 *  - the `cwd` arg is NULLABLE; `null` means ABSENT (fall back to working_dir, blank => the files root);
 *    an explicit relative `cwd` is project-relative, a `/workspace/...` `cwd` is root-absolute — the
 *    Absent-vs-Explicit distinction the old `.orEmpty()` collapsed (W-B2/W-I5 callers);
 *  - resolution order is explicit-override > working_dir > the files root (the unified default, W-M3);
 *  - an ABSENT cwd over an UNSET working_dir resolves to the files root (the project working directory
 *    default the file tools also share), which always exists via ensureWorkspace.
 *
 * The capturing runner records the resolved [WorkspaceShellContext.cwd] (a FILES-relative path) and
 * [WorkspaceShellContext.workingDir] (the real File) the manager hands down — that resolved value is
 * exactly what [ProotShellRunner.prootCwd] then maps to `-w`, so pinning it here pins the value both
 * the runner AND the terminal derive their `-w` from.
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

        // These cwd-resolution tests exercise only the blocking executeCommand path; the non-blocking
        // seam (issue #291) is unused here, so it is intentionally unimplemented.
        override fun startShellRun(
            context: WorkspaceShellContext,
            outputFile: File,
            sizeCapBytes: Long,
        ): ShellRunHandle = throw UnsupportedOperationException("startShellRun unused in cwd-resolution tests")
    }

    private fun manager(runner: WorkspaceShellRunner): WorkspaceManager =
        WorkspaceManager(baseDir = tmp.newFolder("ws"), shellRunner = runner)

    @Test
    fun `absent cwd over unset working_dir resolves to the files root`() {
        val runner = CapturingRunner()
        val mgr = manager(runner)
        mgr.ensureWorkspace("ws1")

        mgr.executeCommand(root = "ws1", command = "echo hi", cwd = null, workingDir = "")

        val ctx = runner.last!!
        // The unified default is the files root (no scratch); it always exists via ensureWorkspace.
        assertEquals("", ctx.cwd)
        assertTrue("the files root must exist", ctx.workingDir.isDirectory)
        assertEquals(mgr.filesDir("ws1").canonicalPath, ctx.workingDir.canonicalPath)
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
    fun `explicit relative cwd is project-relative, a workspace alias is root-absolute`() {
        val runner = CapturingRunner()
        val mgr = manager(runner)
        mgr.ensureWorkspace("ws4")
        File(mgr.filesDir("ws4"), "proj/sub").mkdirs()
        File(mgr.filesDir("ws4"), "sub").mkdirs()

        // A relative cwd is resolved AGAINST working_dir (the unified base), not the files root.
        mgr.executeCommand(root = "ws4", command = "ls", cwd = "sub", workingDir = "proj")
        assertEquals("proj/sub", runner.last!!.cwd)

        // A /workspace alias is root-absolute, working_dir ignored.
        mgr.executeCommand(root = "ws4", command = "ls", cwd = "/workspace/sub", workingDir = "proj")
        assertEquals("sub", runner.last!!.cwd)
    }
}
