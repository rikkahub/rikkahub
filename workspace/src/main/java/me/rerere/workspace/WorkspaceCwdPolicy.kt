package me.rerere.workspace

/**
 * The ONE cwd mapping for a workspace area. Both [ProotShellRunner] and the sideload terminal call
 * [toShellPath]; both go through [normalize] first, so a path accepted by the repository can never
 * become a DIFFERENT path in the terminal vs the PRoot argv (issue #282, W-I6).
 *
 * `working_dir` semantics: "" == UNSET. The stored value is a FILES-area RELATIVE path (the
 * `/workspace` mount), never rootfs-absolute, never per-conversation. Resolution order is
 * explicit-override > working_dir > default `.xcloudz/scratch`.
 *
 * Pure object: no Android imports, no I/O — filesystem materialization lives in `WorkspaceScratch`.
 */
object WorkspaceCwdPolicy {
    const val WORKSPACE_DIR = "/workspace"

    /** `<files>/.xcloudz/scratch` — the resolved default when no override and no working_dir. */
    val DEFAULT_SCRATCH = listOf(".xcloudz", "scratch")

    /** Distinguishes ABSENT (use working_dir/default) from EXPLICIT (use as given, incl. blank). */
    sealed interface CwdOverride {
        data object Absent : CwdOverride
        data class Explicit(val value: String) : CwdOverride
    }

    /**
     * Normalize a FILES-relative path: strip a `/workspace` or `/workspace/<x>` alias prefix,
     * convert `\` -> `/`, drop `.`/empty segments, reject NUL and any `..` segment. A dot-prefixed
     * segment that is NOT `.`/`..` (e.g. `.xcloudz`) is an ORDINARY segment and survives unchanged
     * (W-I9) — the default scratch path must never be stripped.
     *
     * Throws [IllegalArgumentException] on any escape attempt: a `..` segment, a NUL, a
     * whitespace-only segment, OR a rootfs-absolute path that is not the `/workspace` mount alias
     * (W-M6) — `/root/x` is REJECTED, never silently coerced to `root/x`. This is the one funnel both
     * [WorkspaceRepository.setWorkingDir] and `resolveRelative(Absent, workingDir)` pass through, so
     * the absolute-rejection invariant the repository KDoc documents holds at this single source.
     * The result has no leading `/`, no empty/`.` middle segment, and `""` means the files root.
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

    /** "" -> "/workspace"; "a/b" -> "/workspace/a/b". The single source of the PRoot -w value. */
    fun toShellPath(relative: String): String =
        if (relative.isEmpty()) WORKSPACE_DIR else "$WORKSPACE_DIR/$relative"

    /** Inverse of [toShellPath] for `/workspace` aliases (W-I4/W-R2): "/workspace/p" -> "p". */
    fun parseShellPath(shell: String): String = normalize(stripWorkspaceAlias(shell))

    /**
     * The resolved FILES-relative cwd for a command:
     *  - `Explicit("")`/`"."`/whitespace/`/workspace` -> files root `""`            (W-B2)
     *  - `Explicit("/root/...")` (any non-`/workspace` absolute) -> [IllegalArgumentException] (W-M6)
     *  - `Explicit(p)` -> `normalize(p)`
     *  - `Absent` + `workingDir` set -> `normalize(workingDir)`                       (W-M3)
     *  - `Absent` + `workingDir` unset -> [DEFAULT_SCRATCH] joined                    (W-B1)
     */
    fun resolveRelative(override: CwdOverride, workingDir: String): String = when (override) {
        is CwdOverride.Explicit -> resolveExplicit(override.value)
        CwdOverride.Absent ->
            if (workingDir.isBlank()) DEFAULT_SCRATCH.joinToString("/") else normalize(workingDir)
    }

    private fun resolveExplicit(value: String): String {
        val trimmed = value.trim()
        // Blank, lone `.`, and the bare `/workspace` alias all mean "the files root", distinct from
        // ABSENT (which falls through to working_dir/default). Keeping these apart is the whole point
        // of CwdOverride — collapsing them via `.orEmpty()` is the bug this policy exists to kill.
        if (trimmed.isEmpty() || trimmed == "." || isWorkspaceRootAlias(trimmed)) return ""
        // A rootfs-absolute path is not a files-relative cwd; reject it as a cwd ARG (the command
        // TEXT `cd /root && ...` is a different concern and is not seen here).
        require(!isRootfsAbsolute(trimmed)) { "cwd may not be rootfs-absolute: $value" }
        return normalize(trimmed)
    }

    /** Drop a leading `/workspace` or `/workspace/<x>` so the remainder is a files-relative path. */
    private fun stripWorkspaceAlias(path: String): String = when {
        path == WORKSPACE_DIR -> ""
        path.startsWith("$WORKSPACE_DIR/") -> path.removePrefix("$WORKSPACE_DIR/")
        else -> path
    }

    private fun isWorkspaceRootAlias(path: String): Boolean =
        path == WORKSPACE_DIR || path == "$WORKSPACE_DIR/"

    /** True for a rootfs-absolute path (leading `/`) that is NOT a `/workspace` alias. */
    private fun isRootfsAbsolute(path: String): Boolean =
        path.startsWith("/") &&
            !(path == WORKSPACE_DIR || path.startsWith("$WORKSPACE_DIR/"))
}
