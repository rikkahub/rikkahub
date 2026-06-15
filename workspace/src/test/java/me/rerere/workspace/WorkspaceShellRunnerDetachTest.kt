package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the non-blocking shell-runner seam (issue #291, PR-1) against [HostShellRunner], the same
 * device-free runner [ExampleUnitTest]/[ProotShellRunnerTest] use, so CI's JVM gate exercises it
 * with no PRoot rootfs / emulator / network.
 *
 * The seam is the ROOT of the design proposal's STOP_IS_DETACH_NOT_KILL invariant break: today the
 * runner HIDES the [Process] inside [WorkspaceShellRunner.execute], and `readResult` `destroyForcibly`s
 * it on `InterruptedException`, so a caller cannot detach instead of kill. [WorkspaceShellRunner.startShellRun]
 * hands back a [ShellRunHandle] that OWNS the process — `await`/`kill`/`tail` — which PR-2's coordinator
 * uses to detach-rather-than-kill. PR-1 only proves the new seam's hardening (stdin closed, output
 * size-capped) AND that the default blocking path stays byte-identical to today's [execute].
 *
 * Each property maps to a named invariant in the maintainer design proposal and fails on the unfixed
 * tree for the exact reason in its comment (no `startShellRun` exists there at all).
 */
class WorkspaceShellRunnerDetachTest {

    private fun hostShellAvailable(): Boolean =
        File("/system/bin/sh").exists() || File("/bin/sh").exists()

    private fun context(
        command: String,
        outputDir: File,
        timeoutMillis: Long = 30_000,
    ): WorkspaceShellContext {
        val workingDir = Files.createTempDirectory("shellrun-test").toFile()
        return WorkspaceShellContext(
            root = "ws",
            command = command,
            cwd = "",
            filesDir = workingDir,
            linuxDir = File(outputDir, "linux"),
            tempDir = File(outputDir, "tmp"),
            workingDir = workingDir,
            timeoutMillis = timeoutMillis,
        )
    }

    // INLINE_BYTE_COMPAT: a fast command awaited through the new handle returns the SAME
    // WorkspaceCommandResult (exitCode/stdout/stderr/timedOut/truncated) the old blocking execute()
    // produced — the default foreground path must not drift. Fails-before: startShellRun does not exist.
    @Test
    fun `startShellRun await is byte-compatible with execute`() {
        assumeTrue("needs a host /bin/sh", hostShellAvailable())
        val outputDir = Files.createTempDirectory("shellrun-out").toFile()
        val runner = HostShellRunner()
        val command = "printf 'out-line'; printf 'err-line' 1>&2; exit 7"

        val viaExecute = runner.execute(context(command, outputDir))
        val viaHandle = runner.startShellRun(context(command, outputDir), File(outputDir, "h.output"))
            .await()

        assertEquals("exit code identical", viaExecute.exitCode, viaHandle.exitCode)
        assertEquals("stdout identical", viaExecute.stdout, viaHandle.stdout)
        assertEquals("stderr identical", viaExecute.stderr, viaHandle.stderr)
        assertEquals("timedOut identical", viaExecute.timedOut, viaHandle.timedOut)
        assertEquals("truncated identical", viaExecute.truncated, viaHandle.truncated)
        assertEquals(7, viaHandle.exitCode)
        assertEquals("out-line", viaHandle.stdout)
        assertEquals("err-line", viaHandle.stderr)
    }

    // OUTPUT_FILE_WRITTEN: the handle redirects stdout/stderr to the app-private output file (the
    // tail source PR-2's workspace_shell_tail reads); tail() reflects it. Fails-before: no output file.
    @Test
    fun `startShellRun streams output to the file and tail reflects it`() {
        assumeTrue("needs a host /bin/sh", hostShellAvailable())
        val outputDir = Files.createTempDirectory("shellrun-out").toFile()
        val runner = HostShellRunner()
        val outputFile = File(outputDir, "run.output")

        val handle = runner.startShellRun(
            context("printf 'hello-file'", outputDir),
            outputFile,
        )
        val result = handle.await()

        assertEquals(0, result.exitCode)
        assertTrue("output file was created", outputFile.exists())
        assertTrue("output file holds the command output", outputFile.readText().contains("hello-file"))
        assertTrue("byteCount tracks the output bytes", handle.byteCount > 0)
        assertTrue("tail reflects the output", handle.tail(1024).contains("hello-file"))
    }

    // STDIN_CLOSED: today the process stdin is NEVER closed, so a command that reads stdin blocks
    // forever waiting for input that never comes. The handle closes stdin immediately after start, so
    // a `cat`-style read sees EOF and the command terminates instead of hanging to the timeout.
    // Fails-before: stdin is left open and this times out (timedOut=true) instead of exiting cleanly.
    @Test
    fun `startShellRun closes stdin so a stdin-reading command gets EOF and exits`() {
        assumeTrue("needs a host /bin/sh", hostShellAvailable())
        val outputDir = Files.createTempDirectory("shellrun-out").toFile()
        val runner = HostShellRunner()

        // `cat` with no file argument reads stdin to EOF. With stdin closed it sees immediate EOF and
        // exits 0; with stdin left open it blocks until the (short) timeout kills it.
        val handle = runner.startShellRun(
            context("cat", outputDir, timeoutMillis = 5_000),
            File(outputDir, "stdin.output"),
        )
        val result = handle.await()

        assertFalse("must not hang to the timeout — stdin EOF lets it exit", result.timedOut)
        assertEquals("clean exit on stdin EOF", 0, result.exitCode)
    }

    // OUTPUT_CAP_KILLS: a runaway producer that exceeds the output-file size cap is killed by the
    // size watchdog (kill reason KilledSize), and the persisted file stays bounded — it never grows
    // without limit. Fails-before: no size watchdog exists; a `yes`-style loop fills the disk.
    @Test
    fun `startShellRun kills a command that exceeds the output size cap`() {
        assumeTrue("needs a host /bin/sh", hostShellAvailable())
        val outputDir = Files.createTempDirectory("shellrun-out").toFile()
        val runner = HostShellRunner()
        val outputFile = File(outputDir, "runaway.output")

        // An unbounded producer: awk printing forever. The size watchdog must terminate it once the
        // output file passes the cap, well before the 30s timeout.
        val handle = runner.startShellRun(
            context(
                "awk 'BEGIN { while (1) printf \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\" }'",
                outputDir,
            ),
            outputFile,
            sizeCapBytes = 64 * 1024,
        )
        val result = handle.await()

        assertEquals("a size-cap kill is reported as KilledSize", ShellKillReason.KilledSize, handle.killReason)
        assertTrue("the run did not exit cleanly", result.exitCode != 0 || result.timedOut)
        // The sink HARD-caps the on-disk file at sizeCapBytes (drains-and-discards past it), so the
        // file is bounded EXACTLY at the cap regardless of how fast the producer raced the watchdog
        // kill — the unfixed code would grow it to many MB (15 MB in one observed run).
        assertTrue(
            "output file is bounded at the cap (was ${outputFile.length()} bytes)",
            outputFile.length() <= 64L * 1024,
        )
    }

    // KILL_IS_EXPLICIT: kill(KilledTimeout) terminates a long-running command and the handle reports
    // the reason — the seam PR-2's hardTimeout path uses. Fails-before: no kill() on a handle exists.
    @Test
    fun `kill terminates a running command and records the reason`() {
        assumeTrue("needs a host /bin/sh", hostShellAvailable())
        val outputDir = Files.createTempDirectory("shellrun-out").toFile()
        val runner = HostShellRunner()

        val handle = runner.startShellRun(
            context("sleep 30", outputDir, timeoutMillis = 60_000),
            File(outputDir, "killed.output"),
        )
        handle.kill(ShellKillReason.KilledTimeout)
        val result = handle.await()

        assertEquals(ShellKillReason.KilledTimeout, handle.killReason)
        assertTrue("a killed run does not report exit 0", result.exitCode != 0)
    }

    // TIMEOUT_BOUNDARY: the poll loop must never grant the process time past the deadline — a zero
    // (already-expired) deadline is a timeout, exactly as readResult(0) reports it, NOT a free 50ms
    // grace slice. Fails-before: awaitOnce always waited one full WATCHDOG_POLL_MILLIS slice before
    // checking the deadline, so a small/zero timeout could return finished=true / timedOut=false.
    @Test
    fun `startShellRun honors a zero timeout as a timeout, not a grace slice`() {
        assumeTrue("needs a host /bin/sh", hostShellAvailable())
        val outputDir = Files.createTempDirectory("shellrun-out").toFile()
        val runner = HostShellRunner()

        val result = runner.startShellRun(
            context("sleep 5", outputDir, timeoutMillis = 0),
            File(outputDir, "boundary.output"),
        ).await()

        assertTrue("a zero deadline reports a timeout", result.timedOut)
        assertEquals("a timed-out run reports exit -1, like readResult", -1, result.exitCode)
    }

    // CONSTRUCTION_SAFE: if the output sink cannot be opened, the already-started process must not leak
    // and the failure must surface promptly (never hang). Fails-before: startShellRun built the handle
    // without guarding the started process against a sink-open throw, leaking the live process.
    @Test
    fun `startShellRun surfaces a sink-open failure instead of leaking or hanging`() {
        assumeTrue("needs a host /bin/sh", hostShellAvailable())
        val outputDir = Files.createTempDirectory("shellrun-out").toFile()
        // Make the output path unopenable: its parent is a regular FILE, so outputStream() throws.
        val parentIsAFile = File(outputDir, "not-a-dir").apply { writeText("blocker") }
        val unopenable = File(parentIsAFile, "nested.output")
        val runner = HostShellRunner()

        var threw = false
        try {
            runner.startShellRun(context("sleep 30", outputDir, timeoutMillis = 60_000), unopenable)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("the sink-open failure propagates instead of returning a half-built handle", threw)
    }
}
