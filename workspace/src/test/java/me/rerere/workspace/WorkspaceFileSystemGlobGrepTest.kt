package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the recursive glob / content-grep that back workspace_glob and workspace_grep. The matching uses
 * the JVM's java.nio glob PathMatcher, where `*` does NOT cross a path separator and `**` does — these
 * tests lock that behavior (and the case/regex/include-glob options of grep) against a real temp tree.
 */
class WorkspaceFileSystemGlobGrepTest {

    private val fs = WorkspaceFileSystem()

    private fun tempRoot(): File = Files.createTempDirectory("workspace-glob-grep").toFile()

    private fun write(root: File, relativePath: String, content: String) {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun List<WorkspaceFileEntry>.relPaths(): Set<String> =
        map { it.path.replace(File.separatorChar, '/') }.toSet()

    @Test
    fun `glob matches recursively by extension and alternation`() {
        val root = tempRoot()
        write(root, "Root.kt", "// top level")
        write(root, "src/Main.kt", "fun main() {}")
        write(root, "src/util/Helper.kt", "object Helper")
        write(root, "src/data.json", "{}")
        write(root, "README.md", "# hi")

        // ** crosses directories AND, via the globset-style leading-**/ fallback, matches a top-level
        // file (Root.kt) — Java's raw `**/*.kt` would miss the depth-0 file.
        assertEquals(
            setOf("Root.kt", "src/Main.kt", "src/util/Helper.kt"),
            fs.glob(root, "**/*.kt").relPaths(),
        )

        // {a,b} alternation: every .kt (3) and .json (1) under the tree, README.md excluded.
        assertEquals(4, fs.glob(root, "**/*.{kt,json}").size)

        // A single * does not cross '/', so *.md matches only the top-level file.
        assertEquals(setOf("README.md"), fs.glob(root, "*.md").relPaths())
    }

    @Test
    fun `glob rejects a blank pattern`() {
        val root = tempRoot()
        assertThrows(IllegalArgumentException::class.java) { fs.glob(root, "   ") }
    }

    @Test
    fun `grep is literal and case-insensitive by default and reports path and line`() {
        val root = tempRoot()
        write(root, "a.txt", "Hello World\nsecond line\nhello again")
        write(root, "b.kt", "val greeting = HELLO")

        val matches = fs.grep(root, "hello")
        assertEquals(3, matches.size)
        val txtLines = matches.filter { it.path.endsWith("a.txt") }.map { it.line }.toSet()
        assertEquals(setOf(1, 3), txtLines)
    }

    @Test
    fun `grep honors case-sensitivity, regex, and an include glob`() {
        val root = tempRoot()
        write(root, "a.txt", "Hello\nhello")
        write(root, "b.kt", "hello")

        // Case-sensitive literal: only the exact-case "hello" lines (a.txt:2, b.kt:1), not "Hello".
        assertEquals(2, fs.grep(root, "hello", ignoreCase = false).size)

        // Regex anchored to a whole line: same two lines.
        assertEquals(2, fs.grep(root, "^hello$", regex = true, ignoreCase = false).size)

        // includeGlob restricts the searched files to the .kt one.
        val ktOnly = fs.grep(root, "hello", includeGlob = "**/*.kt")
        assertEquals(1, ktOnly.size)
        assertTrue(ktOnly.single().path.endsWith("b.kt"))
    }

    @Test
    fun `grep rejects a blank query`() {
        val root = tempRoot()
        assertThrows(IllegalArgumentException::class.java) { fs.grep(root, "  ") }
    }

    // A hit inside a minified / single-giant-line file must not push a multi-megabyte snippet into the
    // result: the matching line is capped and marked, bounding memory and the serialized tool output.
    @Test
    fun `grep caps a very long matching line`() {
        val root = tempRoot()
        val longLine = "needle " + "x".repeat(5000)
        write(root, "big.txt", longLine)

        val match = fs.grep(root, "needle").single()
        assertTrue("snippet is much shorter than the source line", match.text.length < longLine.length)
        assertTrue("truncation is marked", match.text.endsWith("…(truncated)"))
    }
}
