package me.rerere.workspace

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.withEdgecases
import me.rerere.workspace.WorkspaceCwdPolicy.CwdOverride

/**
 * Kotest [Arb] generators for the pure [WorkspaceCwdPolicy] property suite (issue #282, milestone M1,
 * task T4). These drive every checkAll in [WorkspaceCwdPolicyTest]; keeping them in one file mirrors
 * the production separation -- the policy is one object, its generators are one file.
 *
 * Generator inventory (per the spec testing strategy):
 *  - [arbValidRoot]           a clean, non-empty files-relative path usable as a workspace root subdir.
 *  - [arbSegment]             ONE ordinary path segment, INCLUDING dot-prefixed dirs (`.poci`) so
 *                             the default-scratch path and user dotdirs are exercised (W-I9).
 *  - [arbRelativePath]        1..4 [arbSegment]s joined with `/` -- a clean files-relative path.
 *  - [arbWorkspaceAliasPath]  `/workspace` or `/workspace/<rel>` -- the shell alias (W-M5/W-I4/W-B3).
 *  - [arbRootfsAbsolutePath]  a non-`/workspace` rootfs-absolute path (`/root/..`, `/etc/..`) for W-M6.
 *  - [arbAdversarialPath]     a path that ALWAYS holds an escape token (`..`, NUL, or a blank segment)
 *                             so normalize/resolveRelative MUST reject it (W-B4).
 *  - [arbCwdOverride]         `Absent | Explicit(String)` -- the absent-vs-explicit distinction (W-B2).
 *  - [arbWorkspaceRow]        a stored `working_dir` value: `""` (unset) or a clean relative path.
 */

/** Ordinary segment names, INCLUDING dot-prefixed dirs -- `.poci`/`.hidden` must NOT be stripped. */
private val SEGMENTS = listOf(
    "a", "b", "src", "main", "dir1", "Folder", "node_modules", "build",
    ".poci", "scratch", ".hidden", ".config", ".git",
)

/** Rootfs-absolute prefixes that are NOT the `/workspace` mount; resolving a cwd to one is illegal. */
private val ROOTFS_ABSOLUTES = listOf("/root", "/etc", "/usr", "/proc", "/sys", "/data")

/**
 * Tokens that, spliced anywhere into a path, force normalize to REJECT it:
 *  - `..`        a parent-traversal segment (the escape normalize exists to stop).
 *  - NUL         rejected by the whole-string NUL guard before any split.
 *  - a blank     a whitespace-only segment is not a real directory name.
 * Each forces rejection regardless of the clean segments around it, so an adversarial path built from
 * a clean base plus >= 1 of these is ALWAYS rejected -- no flaky "sometimes valid" sample.
 */
private val ESCAPE_TOKENS = listOf("..", "__NUL__", " ")

fun arbSegment(): Arb<String> = Arb.element(SEGMENTS)

/** A clean files-relative path of 1..4 ordinary (possibly dot-prefixed) segments, no `..`/NUL. */
fun arbRelativePath(): Arb<String> = arbitrary {
    Arb.list(arbSegment(), 1..4).bind().joinToString("/")
}

/** A clean, non-empty relative path to seed a workspace root subdir under a temp files dir (W-I2). */
fun arbValidRoot(): Arb<String> = arbitrary {
    Arb.list(arbSegment(), 1..3).bind().joinToString("/")
}

/** `/workspace` or `/workspace/<rel>` -- the shell-form alias normalize must strip (W-M5/W-I4/W-B3). */
fun arbWorkspaceAliasPath(): Arb<String> = arbitrary {
    if (Arb.boolean().bind()) WorkspaceCwdPolicy.WORKSPACE_DIR
    else "${WorkspaceCwdPolicy.WORKSPACE_DIR}/${arbRelativePath().bind()}"
}

/** A non-`/workspace` rootfs-absolute path; resolving a cwd ARG to one is rejected (W-M6). */
fun arbRootfsAbsolutePath(): Arb<String> = arbitrary {
    val base = Arb.element(ROOTFS_ABSOLUTES).bind()
    if (Arb.boolean().bind()) base else "$base/${arbRelativePath().bind()}"
}

/**
 * A random subset of a FIXED finite [universe] via independent Bernoulli inclusion -- one coin flip
 * per element. Unlike `Arb.set(elementArb, 0..universe.size)` this is TOTAL: it yields a value for
 * EVERY seed, so it never throws the coupon-collector "target size requirement could not be
 * satisfied" that reddened master (memory rikkahub-kotest-arbset-nontotal). The two boundaries --
 * empty and the full universe -- are pinned as edgecases so every run exercises them deterministically.
 */
private fun <T> arbSubsetOf(universe: Collection<T>): Arb<Set<T>> =
    arbitrary { universe.filterTo(mutableSetOf()) { Arb.boolean().bind() } }
        .withEdgecases(emptySet(), universe.toSet())

/**
 * A path that ALWAYS contains >= 1 escape token, so normalize MUST reject it (W-B4). Built by
 * interleaving a clean base path with a Bernoulli-chosen subset of [ESCAPE_TOKENS], then guaranteeing
 * non-emptiness with one more forced token -- total by construction (no retry budget to exhaust).
 */
fun arbAdversarialPath(): Arb<String> = arbitrary {
    val cleanBase = Arb.list(arbSegment(), 0..3).bind()
    val chosen = arbSubsetOf(ESCAPE_TOKENS).bind()
    // Bernoulli may pick the empty subset; force at least one escape token so the path is ALWAYS bad.
    val tokens = if (chosen.isEmpty()) setOf(Arb.element(ESCAPE_TOKENS).bind()) else chosen
    (cleanBase + tokens.toList()).shuffled().joinToString("/").replace("__NUL__", "\u0000")
}

/** `Absent | Explicit(<clean or alias path>)` -- the absent-vs-explicit distinction normalize keeps. */
fun arbCwdOverride(): Arb<CwdOverride> = arbitrary {
    if (Arb.boolean().bind()) {
        CwdOverride.Absent
    } else {
        val inner = if (Arb.boolean().bind()) arbRelativePath().bind() else arbWorkspaceAliasPath().bind()
        CwdOverride.Explicit(inner)
    }
}

/** A stored `working_dir` value: `""` == UNSET, or a clean relative path (already normalized form). */
fun arbWorkspaceRow(): Arb<String> = arbitrary {
    if (Arb.boolean().bind()) "" else arbRelativePath().bind()
}
