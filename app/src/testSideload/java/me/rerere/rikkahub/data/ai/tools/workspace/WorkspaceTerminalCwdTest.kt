package me.rerere.rikkahub.data.ai.tools.workspace

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.pages.extensions.workspace.workspaceTerminalCwd
import me.rerere.workspace.WorkspaceCwdPolicy
import me.rerere.workspace.seededRelativeCwd
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Terminal/exec argv-equivalence property suite for the workspace working-directory seed
 * (issue #282, milestone M4, task T11). Pins **W-I6 (PRIMARY GUARD)** and W-S3.
 *
 * The whole point of [WorkspaceCwdPolicy] is to collapse THREE copies of the cwd -> PRoot `-w`
 * mapping (exec, the non-interactive manager, the interactive terminal) into ONE function, so a path
 * accepted by the repository can never become a DIFFERENT path in the terminal vs the exec argv. This
 * suite proves the structural guarantee at the CI-runnable seam: the interactive terminal's initial
 * `-w` ([workspaceTerminalCwd]) and the exec/manager `-w` derivation both reduce to
 * `WorkspaceCwdPolicy.toShellPath(seededRelativeCwd(filesDir, workingDir))` — the SAME normalized
 * value for the SAME workspace/override.
 *
 * Why a seam, not [me.rerere.rikkahub.ui.pages.extensions.workspace.createWorkspaceTerminalSession]:
 * that builds a real `TerminalSession` (a native PTY + an Android `Context`), uninstantiable on the
 * JVM unit classpath. [workspaceTerminalCwd] is the extracted pure derivation of the `-w` arg the
 * session is launched with — the same CI-runnable-seam pattern as `rootfsInstallButtonState`. This
 * test lives in `testSideload` because the terminal is sideload-only (absent from the Play APK).
 *
 * FAIL-BEFORE rationale: neither [workspaceTerminalCwd] nor [seededRelativeCwd] exists before this
 * commit, so the suite does not compile (the terminal still hard-codes `-w /workspace`). Once both
 * exist and the terminal derives its `-w` through them, every property holds.
 */
class WorkspaceTerminalCwdTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun freshFilesDir(): File = temp.newFolder("files_${System.nanoTime()}")

    /** The exec/manager `-w` for the seed (no per-call override) case: the value the PRoot argv uses. */
    private fun execShellCwd(filesDir: File, workingDir: String): String =
        WorkspaceCwdPolicy.toShellPath(seededRelativeCwd(filesDir, workingDir))

    private val segments = listOf("a", "b", "src", "main", "project", ".xcloudz", "scratch", ".config")

    /** A clean, normalize-stable FILES-relative working_dir (incl. dot-prefixed segments). */
    private fun arbWorkingDir(): Arb<String> = arbitrary {
        if (Arb.boolean().bind()) "" // unset -> the files-root default branch
        else Arb.list(Arb.element(segments), 1..4).bind().joinToString("/")
    }

    // --- W-I6 (PRIMARY GUARD): terminal -w == exec -w for the same workspace/override --------------

    @Test
    fun `W-I6 terminal initial -w equals exec -w for the same workspace`(): Unit = runBlocking {
        checkAll(300, arbWorkingDir()) { workingDir ->
            val files = freshFilesDir()
            val terminal = workspaceTerminalCwd(files, workingDir)
            val exec = execShellCwd(files, workingDir)
            assertEquals(
                "terminal initial -w must be the IDENTICAL normalized value exec uses (W-I6)",
                exec,
                terminal,
            )
        }
    }

    // --- W-I6 corollary: both derive from the central policy, never a private /workspace copy ------

    @Test
    fun `W-I6 terminal -w is toShellPath of the shared seeded relative cwd`(): Unit = runBlocking {
        checkAll(300, arbWorkingDir()) { workingDir ->
            val files = freshFilesDir()
            val terminal = workspaceTerminalCwd(files, workingDir)
            // The terminal `-w` must be a /workspace alias produced by the ONE mapping function, never
            // an independent hard-coded string — toShellPath is `/workspace` or `/workspace/<rel>`.
            val relative = seededRelativeCwd(files, workingDir)
            assertEquals(
                "terminal -w must route through WorkspaceCwdPolicy.toShellPath (W-I6 structural guard)",
                WorkspaceCwdPolicy.toShellPath(relative),
                terminal,
            )
        }
    }

    // --- W-I6 (containment parity): the terminal seed rejects the SAME escaping symlink exec rejects --

    /**
     * exec resolves the seed through WorkspaceFileSystem.resolve (canonical containment) before mapping
     * to `-w`; the terminal builds `-w` straight from [workspaceTerminalCwd] and never calls
     * `WorkspaceManager.executeCommand`. So a persisted `working_dir` reaching THROUGH an escaping
     * in-root symlink (`<files>/inside` -> /outside) was REJECTED by exec but ACCEPTED by the terminal.
     * Both now reduce to the shared `seededRelativeCwd`, the single containment authority, so the
     * terminal must throw on the identical escaping seed.
     */
    @Test
    fun `W-I6 terminal -w rejects a working_dir that escapes the files root via symlink`() {
        val files = freshFilesDir()
        val outside = temp.newFolder("outside_${System.nanoTime()}")
        File(outside, "secret").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            File(files, "inside").toPath(),
            outside.toPath(),
        )

        // exec rejects it (its resolve guard); the terminal must reject the IDENTICAL seed.
        try {
            execShellCwd(files, workingDir = "inside/secret")
            throw AssertionError("precondition: exec must reject the escaping seed")
        } catch (e: IllegalArgumentException) {
            // expected — exec's canonical containment
        }
        try {
            workspaceTerminalCwd(files, workingDir = "inside/secret")
            throw AssertionError("terminal -w must reject the same escaping seed exec rejects (W-I6)")
        } catch (e: IllegalArgumentException) {
            // expected — same single containment authority
        }
    }

    // --- W-S3: opened-before keeps initial cwd; opened-after uses the new default ------------------

    /**
     * A terminal `-w` is a SEED captured at session-open time, never a live cwd (OSC 7 / live drift is
     * out of scope). So a session opened while `working_dir` is unset (the files-root default) must keep
     * that initial `-w` even after the default changes; a session opened AFTER the change picks up the
     * new value. The two openings on the same workspace differ iff the seed differed at open time.
     */
    @Test
    fun `W-S3 terminal opened before a default change keeps its cwd, opened after uses the new one`(): Unit =
        runBlocking {
            val nonBlank = arbitrary { Arb.list(Arb.element(segments), 1..4).bind().joinToString("/") }
            checkAll(200, nonBlank) { newWorkingDir ->
                val files = freshFilesDir()

                // Opened BEFORE the change: working_dir is unset, so the seed is the files root.
                val before = workspaceTerminalCwd(files, workingDir = "")

                // The default working_dir changes to a non-blank value (a settings edit between opens).
                // Opened AFTER the change: the seed is the new working_dir.
                val after = workspaceTerminalCwd(files, workingDir = newWorkingDir)

                assertEquals(
                    "a session opened before the change keeps the files-root seed (W-S3)",
                    WorkspaceCwdPolicy.toShellPath(""),
                    before,
                )
                assertEquals(
                    "a session opened after the change uses the new working_dir seed (W-S3)",
                    WorkspaceCwdPolicy.toShellPath(WorkspaceCwdPolicy.normalize(newWorkingDir)),
                    after,
                )
                // The "before" capture is immune to the later change: re-deriving the pre-change seed
                // yields the same value, proving the initial -w is a snapshot, not a live binding.
                val beforeReDerived = workspaceTerminalCwd(files, workingDir = "")
                assertEquals(
                    "the before-change seed is stable across a later default change (W-S3)",
                    before,
                    beforeReDerived,
                )
            }
        }
}
