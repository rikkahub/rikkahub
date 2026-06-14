package me.rerere.workspace

import java.io.File

/**
 * Lazily, idempotently create the hidden default scratch dir (`<filesDir>/.xcloudz/scratch`) — the
 * resolved default working directory for a workspace when no override and no `working_dir` are set
 * (issue #282, W-B1/W-D2/W-B6).
 *
 * `mkdir-p` both [WorkspaceCwdPolicy.DEFAULT_SCRATCH] segments; creating twice is a no-op and
 * returns the SAME dir, with any tree already inside it preserved (W-D2). If a NON-DIRECTORY already
 * occupies `.xcloudz` or `.xcloudz/scratch`, it is NEVER overwritten or deleted — the helper falls
 * back to the [filesDir] root and returns it, never clobbering user data (W-B6).
 *
 * `java.io.File` only: the relative path mapping (`-w`) lives in the pure [WorkspaceCwdPolicy]; this
 * is the one place that touches the filesystem to materialize that default.
 */
fun ensureDefaultScratch(filesDir: File): File {
    val rootCanon = filesDir.canonicalFile
    var dir = filesDir
    for (segment in WorkspaceCwdPolicy.DEFAULT_SCRATCH) {
        val next = File(dir, segment)
        // A pre-existing non-directory at this segment is a user file we must not destroy. Fall back
        // to the files root rather than mkdir over it / delete it (W-B6) — never clobber. NOTE this
        // alone does NOT catch a symlinked DIRECTORY: `isDirectory` follows links, so a pre-existing
        // `.xcloudz` -> /outside symlink would pass it and `mkdir(File(link, "scratch"))` would write
        // OUTSIDE filesDir before any resolve() containment runs.
        if (next.exists() && !next.isDirectory) return filesDir
        if (!mkdirTolerant(next)) return filesDir
        // Canonical-containment guard (same primitive as WorkspaceFileSystem.resolvePath): the segment
        // we just materialized must still resolve UNDER filesDir. A symlinked segment canonicalizes to
        // its out-of-workspace target and fails this check, so we fall back to the files root rather
        // than follow the link out of the sandbox — the unscoped-write the `!isDirectory` guard misses.
        if (!next.isContainedUnder(rootCanon)) return filesDir
        dir = next
    }
    return dir
}

/** True iff this file's canonical path is [root] itself or a child of it (no symlink escape). */
private fun File.isContainedUnder(root: File): Boolean {
    val canon = canonicalFile.path
    return canon == root.path || canon.startsWith(root.path + File.separator)
}

/**
 * Materialize [dir] as a directory, tolerating a benign concurrent mkdir race. `mkdir()` returns
 * false both when creation genuinely fails AND when another thread/process created the directory
 * between this caller's check and its `mkdir()` (TOCTOU); only the latter is benign. So after a failed
 * `mkdir()` we re-check `isDirectory`: if the directory is now there, the lost race is a success — the
 * dir exists, which is the whole point. Returning false here (the old `!next.mkdir()` path) would make
 * a concurrent first-use caller fall back to the files ROOT while another lands in scratch, splitting
 * the seeded cwd (issue #282, W-I6) and breaking the safe default (W-B1/W-D2).
 *
 * A non-directory blocking the path is NOT a tolerable race: `mkdir()` fails and `isDirectory` stays
 * false, so this returns false and the caller falls back without clobbering (W-B6). The check-then-act
 * `exists()` guard is intentionally absent — `mkdir()`'s own atomic result is the source of truth.
 */
internal fun mkdirTolerant(dir: File): Boolean = dir.mkdir() || dir.isDirectory

/**
 * The ONE resolved FILES-relative cwd for the "no per-call override, seeded from the workspace
 * `working_dir`" case — the value the LLM exec sink and the interactive sideload terminal both map to
 * PRoot `-w` (issue #282, W-I6 primary guard). Both `WorkspaceManager.executeCommand`'s ABSENT branch
 * and the terminal call THIS function so a path accepted by the repository can never become a
 * DIFFERENT path in the exec argv vs the terminal argv.
 *
 * `workingDir` blank == UNSET -> materialize and resolve the default `.xcloudz/scratch` clobber-safely
 * via [ensureDefaultScratch] (which falls back to the files root if a user file already occupies it,
 * W-B6), then report the relative path of the dir it actually returned. Non-blank -> the central
 * policy resolves it (`normalize(workingDir)`); the directory's existence is the caller's concern
 * (exec already `require`s it). This is the seed only — runtime `cd` drift is never written back.
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
): String =
    if (workingDir.isBlank()) {
        ensureDefaultScratch(filesDir).relativeTo(filesDir).path.replace(File.separatorChar, '/')
    } else {
        val relative = WorkspaceCwdPolicy.resolveRelative(WorkspaceCwdPolicy.CwdOverride.Absent, workingDir)
        // Canonical-containment gate (throws IllegalArgumentException on a symlink/`..` escape), same as
        // exec. The returned File is discarded — this call's only job is to REJECT an escaping seed.
        fileSystem.resolve(filesDir, relative)
        relative
    }
