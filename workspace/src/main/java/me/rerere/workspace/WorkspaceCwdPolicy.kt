package me.rerere.workspace

/**
 * The ONE path/cwd mapping for a workspace files area. Both [ProotShellRunner] and the sideload
 * terminal call [toShellPath]; both go through [normalize] first, so a path accepted by the
 * repository can never become a DIFFERENT path in the terminal vs the PRoot argv (issue #282, W-I6).
 *
 * `working_dir` semantics: "" == UNSET, which means "the files root" — the project working directory
 * for both the shell AND the file tools. The stored value is a FILES-area RELATIVE path (the
 * `/workspace` mount), never rootfs-absolute, never per-conversation.
 *
 * UNIFIED PATH MODEL (the file tools and the shell share ONE working-directory base): a model-supplied
 * path is PROJECT-RELATIVE — resolved from `working_dir` (blank => the files root) — UNLESS it starts
 * with the `/workspace` alias, in which case it is ROOT-ABSOLUTE (project dir ignored). [resolveModelPath]
 * is that single resolver; [resolveRelative] applies it to the shell `cwd` arg. So a relative path `X`
 * written by `workspace_write_file` is visible to `workspace_shell` at the same `X` from its default cwd.
 *
 * Pure object: no Android imports, no I/O — the filesystem containment check lives in
 * [seededRelativeCwd] / [WorkspaceFileSystem].
 */
object WorkspaceCwdPolicy {
    const val WORKSPACE_DIR = "/workspace"

    /** Distinguishes ABSENT (use working_dir) from EXPLICIT (use as given, incl. blank). */
    sealed interface CwdOverride {
        data object Absent : CwdOverride
        data class Explicit(val value: String) : CwdOverride
    }

    /**
     * Normalize a FILES-relative path: strip a `/workspace` or `/workspace/<x>` alias prefix,
     * convert `\` -> `/`, drop `.`/empty segments, reject NUL and any `..` segment. A dot-prefixed
     * segment that is NOT `.`/`..` (e.g. `.config`) is an ORDINARY segment and survives unchanged (W-I9).
     *
     * Throws [IllegalArgumentException] on any escape attempt: a `..` segment, a NUL, a
     * whitespace-only segment, OR a rootfs-absolute path that is not the `/workspace` mount alias
     * (W-M6) — `/root/x` is REJECTED, never silently coerced to `root/x`. This is the one funnel both
     * [WorkspaceRepository.setWorkingDir] and the resolvers pass through, so the absolute-rejection
     * invariant the repository KDoc documents holds at this single source. The result has no leading
     * `/`, no empty/`.` middle segment, and `""` means the files root.
     */
    fun normalize(path: String): String {
        require(!path.contains('\u0000')) { "Path contains NUL: $path" }
        val slashed = path.replace('\\', '/')
        // Strip a leading `/workspace` alias so a shell-form path round-trips through the policy.
        val withoutAlias = stripWorkspaceAlias(slashed)
        // A leading `/` that SURVIVED the alias strip is a non-`/workspace` rootfs-absolute path. The
        // empty-segment filter below would otherwise coerce `/root/x` -> `root/x` silently; reject at
        // the source instead so a user-supplied working_dir matches the documented invariant (W-M6).
        require(!withoutAlias.startsWith("/")) { "Path may not be rootfs-absolute: $path" }
        val segments = withoutAlias.split('/')
            .filter { it.isNotEmpty() && it != "." } // drop empty/`.` segments (W-M1)
        segments.forEach { seg ->
            // A `..` segment is an escape attempt; reject at the source, not where the FS crashes.
            require(seg != "..") { "Path escapes workspace root via `..`: $path" }
            // A blank segment (e.g. `a/ /b`, or a lone ` `) is not a real directory name.
            require(seg.isNotBlank()) { "Path contains a blank segment: $path" }
        }
        return segments.joinToString("/")
    }

    /**
     * Resolve a MODEL-supplied path to a files-root-relative path, honoring the workspace project dir.
     * A `/workspace`-alias path is ROOT-ABSOLUTE (project dir ignored); any other path is
     * PROJECT-RELATIVE (joined onto [workingDir], blank => the files root). This is the ONE base both
     * the file tools and the shell `cwd` share, so a relative path `X` addresses the same file in both.
     *
     * Reuses [normalize] for BOTH parts, so `..`/NUL/non-`/workspace`-absolute are rejected at the
     * source; the join of two normalized (no leading `/`, no `..`) paths is itself escape-free, and
     * [WorkspaceFileSystem] applies canonical containment as defense-in-depth. The `/workspace` alias
     * is detected BEFORE normalize (which strips it) so root-absolute and project-relative stay distinct.
     */
    fun resolveModelPath(workingDir: String, modelPath: String): String {
        // Trim the whole path first (matching the old resolveExplicit + WorkspaceFileSystem.resolvePath):
        // a whitespace-only `modelPath` means "the project dir", not a blank-segment rejection.
        val trimmed = modelPath.trim()
        if (isWorkspaceAliasPath(trimmed)) return normalize(trimmed)
        val project = normalize(workingDir)
        val relative = normalize(trimmed)
        return when {
            project.isEmpty() -> relative
            relative.isEmpty() -> project
            else -> "$project/$relative"
        }
    }

    /** "" -> "/workspace"; "a/b" -> "/workspace/a/b". The single source of the PRoot -w value. */
    fun toShellPath(relative: String): String =
        if (relative.isEmpty()) WORKSPACE_DIR else "$WORKSPACE_DIR/$relative"

    /** Inverse of [toShellPath] for `/workspace` aliases (W-I4/W-R2): "/workspace/p" -> "p". */
    fun parseShellPath(shell: String): String = normalize(stripWorkspaceAlias(shell))

    /**
     * True iff [candidate] is the jail [floor] or a descendant of it — the project-dir containment
     * check for the drifting shell cwd. Both args are files-relative normalized paths ("" == the files
     * root). A blank floor (the files root) contains every files-relative path; otherwise [candidate]
     * must equal [floor] or start with `floor + "/"` (so `test` does NOT contain `testify`). Pure string
     * logic — canonical/symlink containment is the filesystem's concern ([WorkspaceFileSystem.resolve]).
     */
    fun isWithin(floor: String, candidate: String): Boolean =
        floor.isEmpty() || candidate == floor || candidate.startsWith("$floor/")

    /**
     * The resolved FILES-relative cwd for a command:
     *  - `Explicit(p)` -> [resolveModelPath]`(workingDir, p)`: a relative `p` is project-relative, a
     *    `/workspace/...` `p` is root-absolute, blank/`.` `p` is the project dir itself          (W-B2)
     *  - `Explicit("/root/...")` (any non-`/workspace` absolute) -> [IllegalArgumentException] (W-M6)
     *  - `Absent` -> `normalize(workingDir)`: the project dir, blank `working_dir` => files root  (W-M3)
     */
    fun resolveRelative(override: CwdOverride, workingDir: String): String = when (override) {
        is CwdOverride.Explicit -> resolveModelPath(workingDir, override.value)
        CwdOverride.Absent -> normalize(workingDir)
    }

    /** Drop a leading `/workspace` or `/workspace/<x>` so the remainder is a files-relative path. */
    private fun stripWorkspaceAlias(path: String): String = when {
        path == WORKSPACE_DIR -> ""
        path.startsWith("$WORKSPACE_DIR/") -> path.removePrefix("$WORKSPACE_DIR/")
        else -> path
    }

    /** True for a path addressed under the `/workspace` mount alias (root-absolute, project-independent). */
    private fun isWorkspaceAliasPath(path: String): Boolean {
        val slashed = path.replace('\\', '/').trim()
        return slashed == WORKSPACE_DIR || slashed.startsWith("$WORKSPACE_DIR/")
    }
}
