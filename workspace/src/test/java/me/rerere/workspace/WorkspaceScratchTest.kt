package me.rerere.workspace

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T3 coverage for the filesystem-materializing [ensureDefaultScratch] (issue #282, milestone M1).
 * Same `@Test` + `runBlocking { checkAll(...) }` convention as [WorkspaceCwdPolicyTest]; this suite
 * touches the real filesystem (via JUnit's [TemporaryFolder]), so it is a small/medium test rather
 * than a pure one. Covers W-D2 (idempotence), W-B1 (default-create), W-B6 (never clobber a non-dir).
 */
class WorkspaceScratchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun scratchDir(filesDir: File): File =
        File(File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]), WorkspaceCwdPolicy.DEFAULT_SCRATCH[1])

    // ---- W-B1: absent cwd + unset row materializes `.xcloudz/scratch` under filesDir ----
    @Test
    fun `W-B1 ensureDefaultScratch creates the hidden scratch dir under filesDir`() {
        val filesDir = tmp.newFolder("files")

        val scratch = ensureDefaultScratch(filesDir)

        assertEquals(scratchDir(filesDir).canonicalFile, scratch.canonicalFile)
        assertTrue("scratch dir must exist", scratch.exists())
        assertTrue("scratch must be a directory", scratch.isDirectory)
        assertEquals(
            ".xcloudz/scratch",
            scratch.canonicalFile.relativeTo(filesDir.canonicalFile).path.replace(File.separatorChar, '/'),
        )
    }

    // ---- W-B1 (property): under any filesDir LOCATION, default-create yields the contained ----
    // `.xcloudz/scratch` directory. Generates an arbitrary (dot-dir-inclusive, via arbRelativePath)
    // location for an EXISTING filesDir — the helper's contract is a real app data dir (Context.filesDir),
    // not a not-yet-created path — so the helper never depends on filesDir being one specific path, and
    // the result is always the same contained scratch dir at the exact relative location.
    @Test
    fun `W-B1 property default-create yields the contained xcloudz scratch dir for any filesDir`() {
        runBlocking {
            checkAll(100, arbRelativePath()) { sub ->
                // An existing files dir at an arbitrary location (Context.filesDir always exists at call time).
                val filesDir = File(tmp.newFolder(), sub).apply { mkdirs() }

                val scratch = ensureDefaultScratch(filesDir)

                assertTrue("scratch dir exists", scratch.exists())
                assertTrue("scratch is a directory", scratch.isDirectory)
                assertEquals(scratchDir(filesDir).canonicalFile, scratch.canonicalFile)
                assertEquals(
                    ".xcloudz/scratch",
                    scratch.canonicalFile.relativeTo(filesDir.canonicalFile).path
                        .replace(File.separatorChar, '/'),
                )
            }
        }
    }

    // ---- W-D2: calling twice returns the SAME dir and preserves any tree already inside it ----
    @Test
    fun `W-D2 ensureDefaultScratch is idempotent and preserves the existing tree`() {
        val filesDir = tmp.newFolder("files")

        val first = ensureDefaultScratch(filesDir)
        // Seed a file inside the scratch dir; a second ensure call must not clobber it.
        val seeded = File(first, "keep.txt").apply { writeText("payload") }

        val second = ensureDefaultScratch(filesDir)

        assertEquals("twice returns the same dir", first.canonicalFile, second.canonicalFile)
        assertTrue("the existing tree is preserved", seeded.exists())
        assertEquals("payload", seeded.readText())
    }

    // ---- W-D2 (property): for an ARBITRARY nested tree, a second ensure preserves it byte-for-byte ----
    // The single-file example above only guards a top-level file; a wrong impl that `deleteRecursively()`d
    // the scratch dir before re-creating it would survive that example yet corrupt a real project tree.
    // This property seeds an arbitrary-depth file path and asserts the second ensure call is a pure no-op.
    @Test
    fun `W-D2 property a second ensure preserves an arbitrary nested tree and returns the same dir`() {
        runBlocking {
            checkAll(
                100,
                arbRelativePath(),
                Arb.string(0..16),
            ) { nested, payload ->
                val filesDir = tmp.newFolder()

                val first = ensureDefaultScratch(filesDir)
                // Materialize an arbitrary-depth file INSIDE the scratch dir, then ensure again.
                val seeded = File(first, "$nested/keep.txt").apply {
                    parentFile?.mkdirs()
                    writeText(payload)
                }

                val second = ensureDefaultScratch(filesDir)

                assertEquals("twice returns the same dir", first.canonicalFile, second.canonicalFile)
                assertTrue("the nested tree is preserved", seeded.exists())
                assertEquals("contents are preserved byte-for-byte", payload, seeded.readText())
            }
        }
    }

    // ---- W-B6: a NON-DIRECTORY at `.xcloudz` is never overwritten; fall back to files root ----
    @Test
    fun `W-B6 a file occupying xcloudz is never clobbered and the files root is returned`() {
        val filesDir = tmp.newFolder("files")
        val xcloudz = File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]).apply { writeText("not a dir") }

        val result = ensureDefaultScratch(filesDir)

        assertEquals("falls back to the files root", filesDir.canonicalFile, result.canonicalFile)
        assertTrue("the occupying file is preserved", xcloudz.exists())
        assertFalse("the occupying file is NOT turned into a directory", xcloudz.isDirectory)
        assertEquals("not a dir", xcloudz.readText())
    }

    // ---- W-B6: a NON-DIRECTORY at `.xcloudz/scratch` (with `.xcloudz` a real dir) is never clobbered ----
    @Test
    fun `W-B6 a file occupying xcloudz scratch is never clobbered and the files root is returned`() {
        val filesDir = tmp.newFolder("files")
        val xcloudz = File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]).apply { mkdirs() }
        val scratchAsFile = File(xcloudz, WorkspaceCwdPolicy.DEFAULT_SCRATCH[1]).apply { writeText("not a dir") }

        val result = ensureDefaultScratch(filesDir)

        assertEquals("falls back to the files root", filesDir.canonicalFile, result.canonicalFile)
        assertTrue("the occupying file is preserved", scratchAsFile.exists())
        assertFalse("the occupying file is NOT turned into a directory", scratchAsFile.isDirectory)
        assertEquals("not a dir", scratchAsFile.readText())
    }

    // ---- W-B1/W-I6 (race): a benign lost-mkdir race must NOT fall back to the files root ----
    // TOCTOU window: a concurrent caller materializes `.xcloudz`/`.xcloudz/scratch` AFTER this caller's
    // `!exists()` check but BEFORE its `mkdir()`, so `mkdir()` returns false even though the dir now
    // exists. The buggy impl (`if (!exists && !mkdir) return filesDir`) then returns the files ROOT
    // instead of the scratch dir, so under concurrent first-use one caller lands in scratch and another
    // in the files root — violating the safe-default (W-B1) and same-cwd (W-I6) guarantees.
    //
    // The race condition reduces to one deterministic primitive: `mkdir()` returns false on a directory
    // that ALREADY EXISTS (standard JDK behaviour). `mkdirTolerant` must treat that as success — the dir
    // is there, which is the whole point. This test exercises exactly that case with no threads/sleeps:
    // the scratch dir is pre-created (the concurrent winner already ran), then `mkdirTolerant` is asked
    // to create it. The buggy `next.mkdir()`-only path returns false here and would lose the dir.
    @Test
    fun `mkdirTolerant treats a lost race (dir already exists) as success`() {
        val filesDir = tmp.newFolder("files")
        val scratch = scratchDir(filesDir).apply { mkdirs() } // concurrent winner already created it

        assertTrue(
            "mkdir() returns false on a pre-existing dir; tolerant create must still succeed",
            mkdirTolerant(scratch),
        )
        assertTrue("the pre-existing dir survives", scratch.isDirectory)
    }

    // ---- W-B1/W-I6 (race, end-to-end): ensureDefaultScratch never returns the files root on a race ----
    // Drives the full helper through a real concurrent race: many callers ensure the default scratch on
    // a FRESH filesDir at once, so the lost-mkdir window is exercised. Every caller MUST resolve to the
    // SAME scratch dir; not one may fall back to the files root. With the buggy impl, a racing caller
    // returns filesDir (W-I6 split-brain); with the fix, all agree. High thread count + barrier makes
    // the window reliably hit; the assertion (no caller sees the root) is correct regardless of timing.
    @Test
    fun `W-I6 concurrent ensureDefaultScratch callers all resolve to the same scratch dir`() {
        val filesDir = tmp.newFolder("files")
        val expected = scratchDir(filesDir).canonicalFile
        val threads = 16
        val barrier = java.util.concurrent.CyclicBarrier(threads)
        val results = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        (1..threads).map {
            Thread {
                barrier.await()
                results.add(ensureDefaultScratch(filesDir).canonicalFile.path)
            }.apply { start() }
        }.forEach { it.join() }

        assertEquals(
            "every concurrent caller must resolve to the one scratch dir, never the files root",
            setOf(expected.path),
            results,
        )
    }

    // ---- W-B6 (symlink escape): a SYMLINKED directory at a scratch segment is never followed ----
    // `File.isDirectory` follows symlinks, so a pre-existing symlink at `.xcloudz` -> /outside passes
    // the non-clobber `!isDirectory` guard, and mkdir(File(symlink, "scratch")) then creates
    // `/outside/scratch` — an unscoped write OUTSIDE filesDir, before any resolve() containment runs.
    // The invariant: every dir ensureDefaultScratch materializes stays canonically under filesDir.
    // Fix falls back to the files root rather than following the link out of the workspace.
    @Test
    fun `W-B6 a symlinked xcloudz pointing outside filesDir is never followed`() {
        val filesDir = tmp.newFolder("files")
        val outside = tmp.newFolder("outside")
        val link = File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0])
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())

        val result = ensureDefaultScratch(filesDir)

        assertEquals("must not follow the link out of the workspace", filesDir.canonicalFile, result.canonicalFile)
        assertFalse(
            "no scratch dir may be created under the out-of-workspace target",
            File(outside, WorkspaceCwdPolicy.DEFAULT_SCRATCH[1]).exists(),
        )
    }

    // ---- W-B6 (symlink escape): a SYMLINK at `.xcloudz/scratch` (with `.xcloudz` a real dir) is never followed ----
    @Test
    fun `W-B6 a symlinked scratch pointing outside filesDir is never followed`() {
        val filesDir = tmp.newFolder("files")
        val outside = tmp.newFolder("outside")
        File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]).mkdirs()
        val link = File(File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]), WorkspaceCwdPolicy.DEFAULT_SCRATCH[1])
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())

        val result = ensureDefaultScratch(filesDir)

        assertEquals("must not follow the link out of the workspace", filesDir.canonicalFile, result.canonicalFile)
    }

    // ---- W-I6 (containment parity): seededRelativeCwd rejects a working_dir that escapes via symlink ----
    // exec resolves the seed through WorkspaceFileSystem.resolve (canonical containment) before mapping
    // to -w; the terminal calls seededRelativeCwd and maps straight to -w with no resolve. So a persisted
    // working_dir reaching through an escaping symlink (`<files>/inside` -> /outside) was REJECTED by exec
    // but ACCEPTED by the terminal — an asymmetry. The shared seededRelativeCwd is the single containment
    // authority, so it must reject the identical set of paths both sinks reduce to.
    @Test
    fun `W-I6 seededRelativeCwd rejects a working_dir escaping the files root via symlink`() {
        val filesDir = tmp.newFolder("files")
        val outside = tmp.newFolder("outside")
        File(outside, "secret").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            File(filesDir, "inside").toPath(),
            outside.toPath(),
        )

        try {
            seededRelativeCwd(filesDir, workingDir = "inside/secret")
            throw AssertionError("seededRelativeCwd must reject a working_dir that escapes via symlink")
        } catch (e: IllegalArgumentException) {
            // expected — the canonical-containment resolver rejects the escape, same as exec
        }
    }

    // ---- W-I6: a contained working_dir (real in-root dir) is accepted and returns the relative path ----
    @Test
    fun `W-I6 seededRelativeCwd accepts a contained working_dir`() {
        val filesDir = tmp.newFolder("files")
        File(filesDir, "project/sub").mkdirs()

        assertEquals("project/sub", seededRelativeCwd(filesDir, workingDir = "project/sub"))
    }

    // ---- W-B6: mkdirTolerant still reports failure when a NON-DIRECTORY blocks the path ----
    @Test
    fun `mkdirTolerant reports failure when a non-directory blocks the path`() {
        val filesDir = tmp.newFolder("files")
        val blocked = File(filesDir, "blocker").apply { writeText("not a dir") }

        assertFalse("a file blocking the path is not a tolerable race", mkdirTolerant(blocked))
        assertFalse("the blocking file is not turned into a directory", blocked.isDirectory)
        assertEquals("not a dir", blocked.readText())
    }

    // ---- W-B6 (property): for any pre-existing non-dir at either segment, never clobber ----
    @Test
    fun `W-B6 property never overwrites an existing non-directory at either scratch segment`() {
        runBlocking {
            checkAll(
                100,
                Arb.element("xcloudz", "scratch"),
                Arb.string(0..8),
            ) { occupy, payload ->
                val filesDir = tmp.newFolder()
                val xcloudz = File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0])
                val blocker = if (occupy == "xcloudz") {
                    xcloudz.apply { writeText(payload) }
                } else {
                    xcloudz.mkdirs()
                    File(xcloudz, WorkspaceCwdPolicy.DEFAULT_SCRATCH[1]).apply { writeText(payload) }
                }

                val result = ensureDefaultScratch(filesDir)

                assertEquals(filesDir.canonicalFile, result.canonicalFile)
                assertTrue("blocker preserved", blocker.exists())
                assertFalse("blocker is still a non-directory", blocker.isDirectory)
                assertEquals(payload, blocker.readText())
            }
        }
    }
}
