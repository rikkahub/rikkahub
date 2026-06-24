package me.rerere.workspace

import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceCwdPolicy.CwdOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Property coverage for the pure [WorkspaceCwdPolicy] (issue #282, milestone M1, task T4). Every test
 * is a JUnit4 `@Test` wrapping `runBlocking { checkAll(...) }` over the shared [WorkspaceCwdArb]
 * generators — the established `:workspace`/automation convention (see [KotestPropertyAvailabilityTest]
 * and `CapabilityGuardPropertyTest`), NOT the Kotest spec runner.
 *
 * Covers W-I1, W-I2, W-I3, W-I4, W-I9, W-M1, W-M2, W-M4, W-M5, W-M6, W-D1, W-B2, W-B3, W-B4, W-B5.
 * W-I2/W-B5 delegate containment to the existing [WorkspaceFileSystem.resolve] canonicalize rule
 * (the spec's single-resolver guarantee) rather than forking a second containment check, so they
 * touch a real temp filesystem; the rest are pure.
 */
class WorkspaceCwdPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = WorkspaceFileSystem()

    // ---- W-I3: toShellPath is /workspace or /workspace/<p> ----
    @Test
    fun `W-I3 toShellPath maps empty to workspace root and a clean path under it`() {
        assertEquals("/workspace", WorkspaceCwdPolicy.toShellPath(""))
        assertEquals("/workspace/a/b", WorkspaceCwdPolicy.toShellPath("a/b"))
        runBlocking {
            checkAll(200, arbRelativePath()) { p ->
                assertEquals("/workspace/$p", WorkspaceCwdPolicy.toShellPath(p))
            }
        }
    }

    // ---- W-I4 / W-R2: parseShellPath is the inverse of toShellPath for /workspace aliases ----
    @Test
    fun `W-I4 parseShellPath is the inverse of toShellPath`() {
        assertEquals("", WorkspaceCwdPolicy.parseShellPath("/workspace"))
        assertEquals("", WorkspaceCwdPolicy.parseShellPath("/workspace/"))
        assertEquals("p", WorkspaceCwdPolicy.parseShellPath("/workspace/p"))
        runBlocking {
            checkAll(200, arbRelativePath()) { p ->
                val shell = WorkspaceCwdPolicy.toShellPath(p)
                assertEquals(p, WorkspaceCwdPolicy.parseShellPath(shell))
            }
            // Any /workspace alias round-trips through parse -> toShell to its canonical shell form.
            checkAll(200, arbWorkspaceAliasPath()) { alias ->
                val rel = WorkspaceCwdPolicy.parseShellPath(alias)
                assertEquals(WorkspaceCwdPolicy.toShellPath(rel), WorkspaceCwdPolicy.toShellPath(WorkspaceCwdPolicy.parseShellPath(alias)))
            }
        }
    }

    // ---- W-I1: normalize yields no NUL, no leading /, no `..` segment, no empty middle segment ----
    @Test
    fun `W-I1 normalize produces a clean relative path`() {
        runBlocking {
            checkAll(300, arbRelativePath()) { p ->
                val n = WorkspaceCwdPolicy.normalize(p)
                assertTrue("no NUL or control char", n.none { it.isISOControl() })
                assertFalse("no leading slash", n.startsWith("/"))
                val segs = if (n.isEmpty()) emptyList() else n.split("/")
                assertTrue("no `..` segment", segs.none { it == ".." })
                assertTrue("no `.` segment", segs.none { it == "." })
                assertTrue("no empty middle segment", segs.none { it.isEmpty() })
            }
        }
    }

    // ---- W-I2: resolve(filesDir, normalize(p)) canonical path is filesDir-or-child ----
    @Test
    fun `W-I2 resolve of a normalized path stays inside the files root`() {
        runBlocking {
            checkAll(200, arbValidRoot()) { p ->
                val filesDir = tmp.newFolder()
                val rootPath = filesDir.canonicalFile.path
                val resolved = fs.resolve(filesDir, WorkspaceCwdPolicy.normalize(p)).canonicalFile.path
                assertTrue(
                    "resolved path must be the files root or a child of it: $resolved vs $rootPath",
                    resolved == rootPath || resolved.startsWith(rootPath + File.separator),
                )
            }
        }
    }

    // ---- W-I9: a dot-prefixed segment != `.`/`..` survives normalization unchanged ----
    @Test
    fun `W-I9 dot-prefixed segments like poci survive normalization`() {
        assertEquals(".config/cache", WorkspaceCwdPolicy.normalize(".config/cache"))
        assertEquals(".hidden", WorkspaceCwdPolicy.normalize(".hidden"))
        runBlocking {
            // Every generated dotdir segment (the generator includes them) survives verbatim.
            checkAll(200, arbSegment()) { seg ->
                assertEquals(seg, WorkspaceCwdPolicy.normalize(seg))
            }
        }
    }

    // ---- W-M1: normalize(p) == normalize("./p") == normalize("p/.") == normalize("p/") ----
    @Test
    fun `W-M1 normalize is invariant under leading and trailing dot segments`() {
        runBlocking {
            checkAll(200, arbRelativePath()) { p ->
                val base = WorkspaceCwdPolicy.normalize(p)
                assertEquals(base, WorkspaceCwdPolicy.normalize("./$p"))
                assertEquals(base, WorkspaceCwdPolicy.normalize("$p/."))
                assertEquals(base, WorkspaceCwdPolicy.normalize("$p/"))
            }
        }
    }

    // ---- W-M2: normalize(backslashed) == normalize(slashed) ----
    @Test
    fun `W-M2 normalize treats backslash and slash identically`() {
        runBlocking {
            checkAll(200, arbRelativePath()) { p ->
                assertEquals(
                    WorkspaceCwdPolicy.normalize(p),
                    WorkspaceCwdPolicy.normalize(p.replace('/', '\\')),
                )
            }
        }
    }

    // ---- W-M4: toShellPath(a/b) == toShellPath(a) + "/" + b  (shell join distributes) ----
    @Test
    fun `W-M4 toShellPath distributes over a path join`() {
        runBlocking {
            checkAll(200, arbSegment(), arbSegment()) { a, b ->
                assertEquals(
                    WorkspaceCwdPolicy.toShellPath("$a/$b"),
                    "${WorkspaceCwdPolicy.toShellPath(a)}/$b",
                )
            }
        }
    }

    // ---- W-D1: normalize is idempotent ----
    @Test
    fun `W-D1 normalize is idempotent`() {
        runBlocking {
            checkAll(300, arbRelativePath()) { p ->
                val once = WorkspaceCwdPolicy.normalize(p)
                assertEquals(once, WorkspaceCwdPolicy.normalize(once))
            }
        }
    }

    // ---- W-M5 / W-B3: a /workspace alias prefix is stripped by normalize ----
    @Test
    fun `W-M5 normalize strips a workspace alias prefix`() {
        assertEquals("", WorkspaceCwdPolicy.normalize("/workspace"))
        assertEquals("", WorkspaceCwdPolicy.normalize("/workspace/"))
        assertEquals(".poci/scratch", WorkspaceCwdPolicy.normalize("/workspace/.poci/scratch"))
        runBlocking {
            checkAll(200, arbRelativePath()) { p ->
                assertEquals(
                    WorkspaceCwdPolicy.normalize(p),
                    WorkspaceCwdPolicy.normalize("/workspace/$p"),
                )
            }
            // Any generated /workspace alias normalizes to the SAME value as its stripped remainder.
            checkAll(200, arbWorkspaceAliasPath()) { alias ->
                val stripped = alias.removePrefix(WorkspaceCwdPolicy.WORKSPACE_DIR).removePrefix("/")
                assertEquals(WorkspaceCwdPolicy.normalize(stripped), WorkspaceCwdPolicy.normalize(alias))
            }
        }
    }

    // ---- W-B3: /workspace, /workspace/, /workspace/.poci/scratch alias to root / the scratch dir ----
    @Test
    fun `W-B3 workspace aliases resolve to the files root or the named subpath`() {
        assertEquals("", WorkspaceCwdPolicy.parseShellPath("/workspace"))
        assertEquals("", WorkspaceCwdPolicy.parseShellPath("/workspace/"))
        assertEquals(
            ".poci/scratch",
            WorkspaceCwdPolicy.parseShellPath("/workspace/.poci/scratch"),
        )
        // As an Explicit cwd ARG, the bare /workspace alias means the files root (W-B2 boundary).
        assertEquals("", WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit("/workspace"), workingDir = "x"))
        assertEquals("", WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit("/workspace/"), workingDir = "x"))
    }

    // ---- W-B4: `..` escapes, NUL, blanks, and parent traversals are rejected by normalize ----
    @Test
    fun `W-B4 normalize rejects parent traversal NUL and blank segments`() {
        listOf("../x", "x/../../y", "/../x", "..", "a/..", " ", "a/ /b", "a/../b").forEach { bad ->
            assertRejected(bad)
        }
        runBlocking {
            // Every generated adversarial path holds >= 1 escape token, so normalize MUST reject it.
            checkAll(400, arbAdversarialPath()) { bad ->
                try {
                    WorkspaceCwdPolicy.normalize(bad)
                    throw AssertionError("expected normalize to reject adversarial path [$bad]")
                } catch (e: IllegalArgumentException) {
                    // expected — a `..` segment, a NUL, or a blank segment is an escape attempt
                }
            }
        }
    }

    // ---- W-B5: a symlink inside files pointing OUTSIDE is rejected; pointing INSIDE is accepted ----
    @Test
    fun `W-B5 symlink escaping the files root is rejected and an inside symlink is accepted`() {
        val filesDir = tmp.newFolder("files")
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("top secret")

        // A symlink at <files>/escape -> <outside> resolves (canonically) outside the root: rejected.
        val escapeLink = File(filesDir, "escape").toPath()
        Files.createSymbolicLink(escapeLink, outside.toPath())
        assertRejectedResolve(filesDir, "escape/secret.txt")

        // A symlink at <files>/inside -> <files>/real (a real in-root dir) stays contained: accepted.
        val realDir = File(filesDir, "real").apply { mkdirs() }
        File(realDir, "ok.txt").writeText("fine")
        val insideLink = File(filesDir, "inside").toPath()
        Files.createSymbolicLink(insideLink, realDir.toPath())
        val resolved = fs.resolve(filesDir, "inside/ok.txt").canonicalFile.path
        assertTrue(
            "an in-root symlink target must stay contained: $resolved",
            resolved.startsWith(filesDir.canonicalFile.path + File.separator),
        )
    }

    // ---- W-B2: explicit blank/dot resolves to the PROJECT dir; a /workspace alias to the files root ----
    // Unified model: a relative (incl. blank/".") explicit cwd is project-relative, so blank == the
    // project dir itself; only a `/workspace` alias is root-absolute (project dir ignored).
    @Test
    fun `W-B2 explicit blank-or-dot resolves to the project dir, a workspace alias to the files root`() {
        // Blank working_dir: the project dir IS the files root, so blank/dot resolve to "".
        listOf("", " ", "  ", ".", "./").forEach { blankish ->
            assertEquals(
                "explicit \"$blankish\" over a blank working_dir is the files root",
                "",
                WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit(blankish), workingDir = ""),
            )
        }
        // Set working_dir: blank/dot resolve to the PROJECT dir (NOT the files root).
        listOf("", " ", ".", "./").forEach { blankish ->
            assertEquals(
                "explicit \"$blankish\" over a set working_dir is the project dir",
                "proj/sub",
                WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit(blankish), workingDir = "proj/sub"),
            )
        }
        // A `/workspace` alias is always the files root, project dir ignored.
        listOf("/workspace", "/workspace/").forEach { alias ->
            assertEquals(
                "explicit \"$alias\" is the files root regardless of working_dir",
                "",
                WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit(alias), workingDir = "proj/sub"),
            )
        }
    }

    // ---- W-M6: normalize itself rejects a rootfs-absolute path (no silent coercion) ----
    // The KDoc on WorkspaceRepository.setWorkingDir asserts "a `..`/NUL/absolute escape is rejected at
    // the source". But normalize only stripped the leading-/ empty segment, turning `/root/x` -> `root/x`
    // — a silent absolute->relative coercion that violates the documented invariant. normalize is the ONE
    // funnel both setWorkingDir AND resolveRelative(Absent, workingDir) use, so the rejection belongs here.
    // The `/workspace` alias is the only absolute that survives (it is the mount alias, stripped first).
    @Test
    fun `W-M6 normalize rejects a rootfs-absolute path instead of coercing it`() {
        listOf("/root", "/root/x", "/etc/passwd", "/usr/bin", "/data/x", "/proc/1").forEach { abs ->
            assertRejected(abs)
        }
        // The /workspace mount alias is NOT rejected — it is stripped to its files-relative remainder.
        assertEquals("", WorkspaceCwdPolicy.normalize("/workspace"))
        assertEquals(".poci/scratch", WorkspaceCwdPolicy.normalize("/workspace/.poci/scratch"))
        runBlocking {
            // Every generated rootfs-absolute path (never a /workspace alias) is rejected by normalize.
            checkAll(300, arbRootfsAbsolutePath()) { abs ->
                assertRejected(abs)
            }
        }
    }

    // ---- W-M6: explicit "/root/..." rejected as a cwd arg (a `cd /root` command TEXT is not seen here) ----
    @Test
    fun `W-M6 explicit rootfs-absolute override is rejected as a cwd arg`() {
        listOf("/root", "/root/x", "/etc/passwd", "/usr/bin").forEach { abs ->
            assertRejectedResolve(abs)
        }
        runBlocking {
            // Every generated rootfs-absolute path (never a /workspace alias) is rejected as a cwd arg.
            checkAll(300, arbRootfsAbsolutePath()) { abs ->
                try {
                    WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit(abs), workingDir = "")
                    throw AssertionError("expected resolveRelative(Explicit(\"$abs\")) to reject")
                } catch (e: IllegalArgumentException) {
                    // expected — a rootfs-absolute path is not a valid files-relative cwd
                }
            }
        }
    }

    // ---- resolveRelative: Explicit(p) for a clean nonblank p normalizes p ----
    @Test
    fun `resolveRelative explicit nonblank normalizes the override`() {
        runBlocking {
            checkAll(200, arbRelativePath()) { p ->
                assertEquals(
                    WorkspaceCwdPolicy.normalize(p),
                    WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit(p), workingDir = ""),
                )
            }
        }
    }

    // ---- resolveModelPath: a /workspace alias is ROOT-ABSOLUTE (project dir ignored) ----
    @Test
    fun `resolveModelPath treats a workspace alias as root-absolute`() {
        assertEquals("", WorkspaceCwdPolicy.resolveModelPath(workingDir = "proj", modelPath = "/workspace"))
        assertEquals("foo/bar", WorkspaceCwdPolicy.resolveModelPath(workingDir = "proj", modelPath = "/workspace/foo/bar"))
        runBlocking {
            checkAll(200, arbRelativePath(), arbRelativePath()) { wd, p ->
                assertEquals(
                    "a /workspace alias resolves to its stripped remainder, project dir ignored",
                    WorkspaceCwdPolicy.normalize(p),
                    WorkspaceCwdPolicy.resolveModelPath(workingDir = wd, modelPath = "/workspace/$p"),
                )
            }
        }
    }

    // ---- resolveModelPath: a RELATIVE path is joined onto the project dir (blank => the files root) ----
    @Test
    fun `resolveModelPath joins a relative path onto the project dir`() {
        assertEquals("foo", WorkspaceCwdPolicy.resolveModelPath(workingDir = "", modelPath = "foo"))
        assertEquals("proj/foo", WorkspaceCwdPolicy.resolveModelPath(workingDir = "proj", modelPath = "foo"))
        assertEquals("proj", WorkspaceCwdPolicy.resolveModelPath(workingDir = "proj", modelPath = ""))
        assertEquals("proj", WorkspaceCwdPolicy.resolveModelPath(workingDir = "proj", modelPath = "."))
        assertEquals("", WorkspaceCwdPolicy.resolveModelPath(workingDir = "", modelPath = ""))
        runBlocking {
            checkAll(200, arbRelativePath(), arbRelativePath()) { wd, p ->
                assertEquals(
                    "${WorkspaceCwdPolicy.normalize(wd)}/${WorkspaceCwdPolicy.normalize(p)}",
                    WorkspaceCwdPolicy.resolveModelPath(wd, p),
                )
            }
        }
    }

    // ---- resolveModelPath: the canonical /workspace output round-trips with NO double-join (the ----
    // load-bearing decision). A tool reports toShellPath(resolved); reading that back as a model path
    // re-resolves to the IDENTICAL files-relative value for ANY project dir, so list -> read never
    // joins a project-relative path onto the project dir twice.
    @Test
    fun `resolveModelPath canonical output re-resolves to the same path under any project dir`() {
        runBlocking {
            checkAll(200, arbWorkspaceRow(), arbRelativePath()) { wd, p ->
                val resolved = WorkspaceCwdPolicy.resolveModelPath(wd, p)
                val canonical = WorkspaceCwdPolicy.toShellPath(resolved)
                assertEquals(
                    "the canonical /workspace output must re-resolve to the same files-relative path",
                    resolved,
                    WorkspaceCwdPolicy.resolveModelPath(workingDir = "any/other", modelPath = canonical),
                )
            }
        }
    }

    // ---- resolveModelPath: `..`/NUL/rootfs-absolute model paths are rejected at the source ----
    @Test
    fun `resolveModelPath rejects escapes and rootfs-absolute paths`() {
        listOf("../x", "a/../../b", "/etc/passwd", "/root", "a/ /b").forEach { bad ->
            try {
                WorkspaceCwdPolicy.resolveModelPath(workingDir = "proj", modelPath = bad)
                throw AssertionError("expected resolveModelPath to reject [$bad]")
            } catch (e: IllegalArgumentException) {
                // expected — defense at the source via normalize
            }
        }
    }

    // ---- W-UNIFY: a relative file path is visible to the shell at the same name from its default cwd ----
    // The whole point of the unified model: the file tools resolve a relative model path via
    // resolveModelPath(wd, X); the shell's default (ABSENT) cwd is resolveRelative(Absent, wd). For the
    // SAME project dir, the file's resolved path is EXACTLY "<shell default cwd>/X" — one shared
    // working-directory base, so `workspace_write_file("X")` lands where `workspace_shell` (no cwd) runs.
    @Test
    fun `W-UNIFY a relative file path resolves under the shell default cwd`() {
        runBlocking {
            checkAll(200, arbWorkspaceRow(), arbRelativePath()) { wd, x ->
                val fileResolved = WorkspaceCwdPolicy.resolveModelPath(wd, x)
                val shellCwd = WorkspaceCwdPolicy.resolveRelative(CwdOverride.Absent, wd)
                val nx = WorkspaceCwdPolicy.normalize(x)
                val expected = if (shellCwd.isEmpty()) nx else "$shellCwd/$nx"
                assertEquals(
                    "a relative file path must live under the shell's default cwd (the unified base)",
                    expected,
                    fileResolved,
                )
            }
        }
    }

    // ---- isWithin: the project-jail containment for the drifting shell cwd ----
    @Test
    fun `isWithin contains the floor and its descendants only`() {
        // A blank floor (the files root) contains every files-relative path.
        assertTrue(WorkspaceCwdPolicy.isWithin("", ""))
        assertTrue(WorkspaceCwdPolicy.isWithin("", "anything/deep"))
        // A set floor contains itself and its descendants.
        assertTrue(WorkspaceCwdPolicy.isWithin("test", "test"))
        assertTrue(WorkspaceCwdPolicy.isWithin("test", "test/subdir"))
        assertTrue(WorkspaceCwdPolicy.isWithin("test", "test/a/b/c"))
        // ...but NOT the files root (ancestor), a sibling, an ancestor of the floor, or a prefix-not-subdir.
        assertFalse(WorkspaceCwdPolicy.isWithin("test", ""))             // /workspace, above the floor
        assertFalse(WorkspaceCwdPolicy.isWithin("test", "non-subdir"))   // sibling subtree
        assertFalse(WorkspaceCwdPolicy.isWithin("test/subdir", "test"))  // ancestor of the floor
        assertFalse(WorkspaceCwdPolicy.isWithin("test", "testify"))      // shared prefix, not a subdir
        runBlocking {
            checkAll(200, arbRelativePath(), arbRelativePath()) { floor, rel ->
                assertTrue("a floor always contains itself", WorkspaceCwdPolicy.isWithin(floor, floor))
                assertTrue("a floor contains its descendants", WorkspaceCwdPolicy.isWithin(floor, "$floor/$rel"))
            }
        }
    }

    // ---- constants pinned ----
    @Test
    fun `policy constants are pinned`() {
        assertEquals("/workspace", WorkspaceCwdPolicy.WORKSPACE_DIR)
    }

    private fun assertRejected(bad: String) {
        try {
            WorkspaceCwdPolicy.normalize(bad)
            throw AssertionError("expected normalize(\"$bad\") to reject, but it did not")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertRejectedResolve(abs: String) {
        try {
            WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit(abs), workingDir = "")
            throw AssertionError("expected resolveRelative(Explicit(\"$abs\")) to reject, but it did not")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertRejectedResolve(root: File, path: String) {
        try {
            fs.resolve(root, path)
            throw AssertionError("expected resolve(\"$path\") to reject (escape), but it did not")
        } catch (e: IllegalArgumentException) {
            // expected — the resolver's canonical-containment check rejects the escape
        }
    }
}
