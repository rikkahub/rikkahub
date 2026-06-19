package me.rerere.workspace

import java.io.File

/**
 * The ONE resolved FILES-relative cwd for the "no per-call override, seeded from the workspace
 * `working_dir`" case — the value the LLM exec sink and the interactive sideload terminal both map to
 * PRoot `-w` (issue #282, W-I6 primary guard). Both `WorkspaceManager.executeCommand`'s ABSENT branch
 * and the terminal call THIS function so a path accepted by the repository can never become a
 * DIFFERENT path in the exec argv vs the terminal argv.
 *
 * `working_dir` blank == UNSET -> the files root `""`: the project working directory default that the
 * file tools also resolve relative paths against (the unified base; the files root always exists via
 * `ensureWorkspace`). Non-blank -> the central policy resolves it (`normalize(workingDir)`); the
 * directory's existence is the caller's concern (exec already `require`s it). This is the seed only —
 * runtime `cd` drift is never written back.
 *
 * CONTAINMENT (W-I6): this is the SINGLE containment authority both sinks share. The non-blank seed is
 * resolved through [WorkspaceFileSystem.resolve] — the SAME canonical-containment check exec applies —
 * so a `working_dir` that reaches THROUGH an escaping in-root symlink is rejected HERE, before either
 * the exec argv or the terminal argv is built. Without this the terminal (which never calls
 * `WorkspaceManager.executeCommand` and so skips its post-resolve guard) would accept an escaping seed
 * that exec rejects — the exec/terminal asymmetry this function exists to kill. exec's later
 * `fileSystem.resolve` becomes an idempotent re-check; the terminal gets the gate it was missing.
 */
fun seededRelativeCwd(
    filesDir: File,
    workingDir: String,
    fileSystem: WorkspaceFileSystem = WorkspaceFileSystem(),
): String {
    if (workingDir.isBlank()) return ""
    val relative = WorkspaceCwdPolicy.resolveRelative(WorkspaceCwdPolicy.CwdOverride.Absent, workingDir)
    // Canonical-containment gate (throws IllegalArgumentException on a symlink/`..` escape), same as
    // exec. The returned File is discarded — this call's only job is to REJECT an escaping seed.
    fileSystem.resolve(filesDir, relative)
    return relative
}
