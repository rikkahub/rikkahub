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
    fun `W-I9 dot-prefixed segments like xcloudz survive normalization`() {
        assertEquals(".xcloudz/scratch", WorkspaceCwdPolicy.normalize(".xcloudz/scratch"))
        assertEquals(".hidden", WorkspaceCwdPolicy.normalize(".hidden"))
        assertEquals(
            WorkspaceCwdPolicy.DEFAULT_SCRATCH.joinToString("/"),
            WorkspaceCwdPolicy.normalize(".xcloudz/scratch"),
        )
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
        assertEquals(".xcloudz/scratch", WorkspaceCwdPolicy.normalize("/workspace/.xcloudz/scratch"))
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

    // ---- W-B3: /workspace, /workspace/, /workspace/.xcloudz/scratch alias to root / the scratch dir ----
    @Test
    fun `W-B3 workspace aliases resolve to the files root or the named subpath`() {
        assertEquals("", WorkspaceCwdPolicy.parseShellPath("/workspace"))
        assertEquals("", WorkspaceCwdPolicy.parseShellPath("/workspace/"))
        assertEquals(
            ".xcloudz/scratch",
            WorkspaceCwdPolicy.parseShellPath("/workspace/.xcloudz/scratch"),
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

    // ---- W-B2: explicit "", " ", ".", "./", /workspace resolve to the files root (NOT the scratch default) ----
    @Test
    fun `W-B2 explicit blank-or-dot override resolves to the files root`() {
        listOf("", " ", "  ", ".", "./", "/workspace", "/workspace/").forEach { blankish ->
            assertEquals(
                "explicit \"$blankish\" must resolve to files root",
                "",
                WorkspaceCwdPolicy.resolveRelative(CwdOverride.Explicit(blankish), workingDir = "ignored"),
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
        assertEquals(".xcloudz/scratch", WorkspaceCwdPolicy.normalize("/workspace/.xcloudz/scratch"))
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

    // ---- constants pinned ----
    @Test
    fun `policy constants are pinned`() {
        assertEquals("/workspace", WorkspaceCwdPolicy.WORKSPACE_DIR)
        assertEquals(listOf(".xcloudz", "scratch"), WorkspaceCwdPolicy.DEFAULT_SCRATCH)
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
