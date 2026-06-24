package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the shell-command escaping form (hardening #3 / upstream 76bd17af). The command must be passed
 * to bash as a positional arg ($2) and `eval "$2"`'d exactly once — NOT inlined into the `-c` script,
 * which let bash expand `$`/backticks at parse time, before eval, corrupting commands with shell
 * metacharacters (the lethal case for an LLM-driven shell on untrusted input). buildCommand is pure
 * (no process spawned, no rootfs needed), so the form is CI-runnable without PRoot.
 */
class ProotShellRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    /** Run the capture `-c` script with a REAL bash ($1 = a real temp dir, $2 = the user command). */
    private fun runCaptureScript(command: String): Pair<Int, String> {
        val dir = tmp.newFolder()
        val ctx = context(command).copy(cwdCaptureToken = RB_TOKEN)
        val script = ProotShellRunner(File("/tmp/native")).cScript(ctx)
        // Mirror production: a startup-free shell (no profile/rc), so a poisoned rootfs profile cannot
        // shadow the wrapper's verbs before the -c script runs.
        val proc = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", script, "rikkahub", dir.absolutePath, command)
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        return proc.waitFor() to out
    }

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
    fun `cwd capture postlude runs outside the eval payload and keeps the command a raw positional`() {
        val runner = ProotShellRunner(File("/tmp/native"))
        val token = "__rikkahub_cwd_TOKEN__"
        val command = "echo hi && cd sub"
        val ctx = context(command).copy(cwdCaptureToken = token)

        val argv = runner.buildCommand(ctx, File("/tmp/native/libproot_exec.so"))

        val cIndex = argv.indexOf("-c")
        val body = argv[cIndex + 1]
        // The cwd is captured from an EXIT trap installed BEFORE the user command, and the exit code is
        // NEVER rewritten — the LAST statement is the eval-once form, so the process keeps the user
        // command's natural status (nothing for a user exit()/trap/set -e to corrupt).
        assertTrue(
            "marker is an INLINE EXIT trap wrapped in `! { }` so it cannot flip the exit code",
            body.contains("trap '! { builtin printf \"%s%s\\n\" \"$token\" \"\$(builtin pwd -P)\"; } 2>/dev/null' EXIT"),
        )
        assertTrue("the eval-once form is the last statement", body.endsWith("cd -- \"\$1\" && eval \"\$2\""))
        assertFalse("the script must NOT rewrite the exit code", body.contains("exit \$"))
        // The command text is NEVER inlined into the -c script; it stays the raw last positional arg.
        assertFalse("command must not be inlined into the -c script", body.contains("echo hi"))
        assertEquals("command is the raw last positional", command, argv.last())
    }

    // Without a capture token the -c body is byte-identical to the historic eval-once form.
    @Test
    fun `no capture token keeps the eval-once body unchanged`() {
        val runner = ProotShellRunner(File("/tmp/native"))
        val argv = runner.buildCommand(context("ls"), File("/tmp/native/libproot_exec.so"))
        val cIndex = argv.indexOf("-c")
        assertEquals("cd -- \"\$1\" && eval \"\$2\"", argv[cIndex + 1])
    }

    // The programmatic shell is startup-free (no login profile/rc), so a poisoned rootfs profile cannot
    // shadow the wrapper's verbs before the -c script runs.
    @Test
    fun `shell is launched startup-free, not as a login shell`() {
        val runner = ProotShellRunner(File("/tmp/native"))
        val argv = runner.buildCommand(context("ls"), File("/tmp/native/libproot_exec.so"))
        assertTrue("must pass --noprofile", argv.contains("--noprofile"))
        assertTrue("must pass --norc", argv.contains("--norc"))
        assertFalse("must NOT be a login shell", argv.contains("-l"))
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

        listOf("", ".poci/scratch", "project/sub").forEach { cwd ->
            val argv = runner.buildCommand(context("ls", cwd = cwd), proot)
            val expected = WorkspaceCwdPolicy.toShellPath(cwd)
            val wIndex = argv.indexOf("-w")
            assertEquals("the -w value must come from WorkspaceCwdPolicy.toShellPath", expected, argv[wIndex + 1])
            val cIndex = argv.indexOf("-c")
            assertEquals("the \$1 cwd positional must come from WorkspaceCwdPolicy.toShellPath", expected, argv[cIndex + 3])
        }
    }

    // --- real-bash robustness of the cwd capture (codex P1) ---------------------------------------
    // The capture runs in the SAME shell as the user command, which can install functions/traps that
    // shadow builtins. The design is robust because the exit code is NEVER rewritten (natural process
    // status) and the marker is a best-effort EXIT trap. These run a REAL bash (skipped where
    // unavailable), the established :workspace pattern (see ExampleUnitTest).

    @Test
    fun `capture reports the real exit code despite a shadowing exit function`() {
        assumeTrue("requires /bin/bash", File("/bin/bash").canExecute())

        val (code, out) = runCaptureScript("exit(){ builtin true; }; false")

        assertEquals("the real exit code (false=1) must survive a shadowing exit() function", 1, code)
        assertTrue("the cwd marker is still emitted (EXIT trap)", out.contains(RB_TOKEN))
    }

    @Test
    fun `capture emits a correct marker despite shadowing printf and pwd functions`() {
        assumeTrue("requires /bin/bash", File("/bin/bash").canExecute())

        val (code, out) = runCaptureScript("printf(){ :; }; pwd(){ echo /fake; }; true")

        assertEquals(0, code)
        assertTrue("builtin printf must still emit the marker", out.contains(RB_TOKEN))
        assertFalse("builtin pwd -P must report the real cwd, not the shadowing /fake", out.contains("/fake"))
    }

    // A user EXIT trap overrides ours: the command genuinely exits 0 (its own `trap 'exit 0' EXIT`), so
    // reporting 0 is TRUTHFUL — there is no forced exit code to corrupt — and no marker is emitted, so
    // the caller safely keeps the prior cwd.
    @Test
    fun `capture reports the truthful exit code and no marker when the user overrides the EXIT trap`() {
        assumeTrue("requires /bin/bash", File("/bin/bash").canExecute())

        val (code, out) = runCaptureScript("trap 'exit 0' EXIT; false")

        assertEquals("the command's own trap exits 0 — that is its true status", 0, code)
        assertFalse("our marker trap was overridden — no marker, keep prior cwd", out.contains(RB_TOKEN))
    }

    // Even shadowing `builtin` itself only suppresses the (advisory) marker — the exit code is the
    // natural process status, never rewritten, so a `false` still reports 1.
    @Test
    fun `capture reports the real exit code even when builtin is shadowed`() {
        assumeTrue("requires /bin/bash", File("/bin/bash").canExecute())

        val (code, _) = runCaptureScript("builtin(){ :; }; false")

        assertEquals("the exit code is the natural process status, immune to a shadowed builtin", 1, code)
    }

    // The trap body is INLINE, so a user command defining a function named like a handler cannot hijack
    // it — the exit code stays the natural status, and the marker still fires.
    @Test
    fun `capture is immune to a user function masquerading as the trap handler`() {
        assumeTrue("requires /bin/bash", File("/bin/bash").canExecute())

        val (code, out) = runCaptureScript("__rikkahub_cap(){ exit 0; }; false")

        assertEquals("an inline trap can't be redefined; false still reports 1", 1, code)
        assertTrue("the inline marker still fires", out.contains(RB_TOKEN))
    }

    // errexit + a failing capture verb must NOT flip a SUCCESSFUL command to failure: the `! { }`
    // wrapper suppresses errexit in the trap, so the natural exit code survives. (codex repro)
    @Test
    fun `capture cannot flip a successful command to failure under errexit with a broken builtin`() {
        assumeTrue("requires /bin/bash", File("/bin/bash").canExecute())

        val (disabledPrintf, _) = runCaptureScript("set -e; enable -n printf; true")
        assertEquals("a successful command stays 0 even when the marker printf is disabled", 0, disabledPrintf)

        val (shadowedBuiltin, _) = runCaptureScript("set -e; builtin(){ false; }; true")
        assertEquals("a successful command stays 0 even when builtin is shadowed under set -e", 0, shadowedBuiltin)
    }

    private companion object {
        const val RB_TOKEN = "__rikkahub_cwd_RBTEST__"
    }
}
