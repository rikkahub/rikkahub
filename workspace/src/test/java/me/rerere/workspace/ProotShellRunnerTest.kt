package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the shell-command escaping form (hardening #3 / upstream 76bd17af). The command must be passed
 * to bash as a positional arg ($2) and `eval "$2"`'d exactly once — NOT inlined into the `-c` script,
 * which let bash expand `$`/backticks at parse time, before eval, corrupting commands with shell
 * metacharacters (the lethal case for an LLM-driven shell on untrusted input). buildCommand is pure
 * (no process spawned, no rootfs needed), so the form is CI-runnable without PRoot.
 */
class ProotShellRunnerTest {

    private fun context(command: String, cwd: String = "") = WorkspaceShellContext(
        root = "ws",
        command = command,
        cwd = cwd,
        filesDir = File("/tmp/ws/files"),
        linuxDir = File("/tmp/ws/linux"),
        tempDir = File("/tmp/ws/tmp"),
        workingDir = File("/tmp/ws/files"),
        timeoutMillis = 30_000,
    )

    @Test
    fun `command is passed as a positional arg and eval'd once, never inlined`() {
        val runner = ProotShellRunner(File("/tmp/native"))
        val command = "echo \$HOME && ls `pwd`"

        val argv = runner.buildCommand(context(command), File("/tmp/native/libproot_exec.so"))

        // The -c script body is the fixed positional form; the command text is NOT interpolated into it.
        val cIndex = argv.indexOf("-c")
        assertTrue("bash -c must be present", cIndex >= 0)
        assertEquals("cd -- \"\$1\" && eval \"\$2\"", argv[cIndex + 1])
        // $0=rikkahub, $1=cwd, $2=the RAW command (unescaped, never expanded at parse time).
        assertEquals("rikkahub", argv[cIndex + 2])
        assertEquals(command, argv.last())
        // The dangerous inline form (command substituted into the -c script) must never appear.
        assertFalse(
            "the command must not be inlined into the -c script",
            argv[cIndex + 1].contains("echo") || argv[cIndex + 1].contains("HOME"),
        )
    }

    @Test
    fun `cwd is passed as a positional arg, not inlined`() {
        val runner = ProotShellRunner(File("/tmp/native"))

        val argv = runner.buildCommand(context("ls", cwd = "sub/dir"), File("/tmp/native/libproot_exec.so"))

        val cIndex = argv.indexOf("-c")
        // $1 is the proot cwd (under /workspace); the script references it as "$1", never inlined.
        assertEquals("/workspace/sub/dir", argv[cIndex + 3])
    }

    // The runner's cwd -> -w mapping MUST be the ONE central policy function, not a private copy
    // (issue #282 W-I6): the -w value (after `-w`, and the $1 positional) is exactly
    // WorkspaceCwdPolicy.toShellPath(context.cwd) for every cwd the manager hands down. A blank cwd
    // (the resolved files root) maps to bare /workspace; a relative cwd maps to /workspace/<cwd>.
    @Test
    fun `prootCwd delegates to the central WorkspaceCwdPolicy mapping`() {
        val runner = ProotShellRunner(File("/tmp/native"))
        val proot = File("/tmp/native/libproot_exec.so")

        listOf("", ".xcloudz/scratch", "project/sub").forEach { cwd ->
            val argv = runner.buildCommand(context("ls", cwd = cwd), proot)
            val expected = WorkspaceCwdPolicy.toShellPath(cwd)
            val wIndex = argv.indexOf("-w")
            assertEquals("the -w value must come from WorkspaceCwdPolicy.toShellPath", expected, argv[wIndex + 1])
            val cIndex = argv.indexOf("-c")
            assertEquals("the \$1 cwd positional must come from WorkspaceCwdPolicy.toShellPath", expected, argv[cIndex + 3])
        }
    }
}
