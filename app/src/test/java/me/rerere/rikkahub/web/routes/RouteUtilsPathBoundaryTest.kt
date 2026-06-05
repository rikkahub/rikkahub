package me.rerere.rikkahub.web.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression test for issue #78: GET /api/files/path/{...} used a raw
 * `file.canonicalPath.startsWith(filesDir.canonicalPath)` containment check (FilesRoutes.kt:131).
 *
 * That comparison has no separator boundary, so a SIBLING directory whose name merely shares the
 * prefix of the files dir (e.g. root `/data/.../files` vs `/data/.../files_evil`) canonically
 * starts with the root string and is wrongly accepted — a directory-escape that lets the embedded
 * web server serve files outside the app's managed files directory.
 *
 * The fix moves the invariant into the pure [isPathWithin] predicate (RouteUtils.kt), which compares
 * canonical paths with [File.separator] awareness. These cases drive that predicate directly with
 * real on-disk dirs, so they run in the project's `testDebugUnitTest` JVM gate (no Ktor/Android).
 *
 * Decisive case: [siblingSharingPrefixIsRejected] FAILS on the unfixed raw-startsWith logic for the
 * exact reason in the issue, and passes once the separator-aware boundary is in place.
 */
class RouteUtilsPathBoundaryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File

    private fun setUpRoot(): File {
        root = tempFolder.newFolder("files")
        return root
    }

    @Test
    fun fileDirectlyInRootIsWithin() {
        val r = setUpRoot()
        val target = File(r, "a.txt").apply { writeText("ok") }
        assertTrue(isPathWithin(r, target))
    }

    @Test
    fun fileInSubdirectoryIsWithin() {
        val r = setUpRoot()
        val sub = File(r, "sub").apply { mkdirs() }
        val target = File(sub, "a.txt").apply { writeText("ok") }
        assertTrue(isPathWithin(r, target))
    }

    @Test
    fun rootItselfIsWithin() {
        val r = setUpRoot()
        // Predicate contract: the root dir is considered "within". Serving the dir itself is
        // separately prevented by the route's `!file.isFile` check.
        assertTrue(isPathWithin(r, r))
    }

    @Test
    fun siblingSharingPrefixIsRejected() {
        val r = setUpRoot()
        // Sibling whose canonical path is `<root>_evil` — its string DOES start with the root
        // string, so the old raw `startsWith` accepted it. Separator-aware boundary rejects it.
        val sibling = File(r.parentFile, r.name + "_evil").apply { mkdirs() }
        val target = File(sibling, "a.txt").apply { writeText("leak") }
        assertFalse(isPathWithin(r, target))
    }

    @Test
    fun dotDotEscapeIsRejected() {
        val r = setUpRoot()
        // Canonicalization resolves the `..` segment to a path outside the root.
        val target = File(r, "../outside.txt")
        assertFalse(isPathWithin(r, target))
    }

    @Test
    fun parentDirectoryIsRejected() {
        val r = setUpRoot()
        assertFalse(isPathWithin(r, r.parentFile))
    }

    @Test
    fun anySiblingSuffixWithoutSeparatorIsRejected() {
        val r = setUpRoot()
        for (suffix in listOf("x", "_evil", "2", "-backup", ".bak", "abc123")) {
            val sibling = File(r.parentFile, r.name + suffix)
            assertFalse(
                "sibling '${sibling.name}' must be rejected",
                isPathWithin(r, sibling)
            )
        }
    }
}
