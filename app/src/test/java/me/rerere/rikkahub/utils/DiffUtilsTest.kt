package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffUtilsTest {

    @Test
    fun `returns null when texts are identical`() {
        assertNull(generateUnifiedDiff("a\nb\nc", "a\nb\nc", "file.txt"))
    }

    @Test
    fun `generates unified diff for single line replacement`() {
        val diff = generateUnifiedDiff(
            oldText = "line1\nline2\nline3",
            newText = "line1\nchanged\nline3",
            path = "file.txt",
        )
        assertEquals(
            """
            --- a/file.txt
            +++ b/file.txt
            @@ -1,3 +1,3 @@
             line1
            -line2
            +changed
             line3
            """.trimIndent(),
            diff,
        )
    }

    @Test
    fun `generates diff for appended lines`() {
        val diff = generateUnifiedDiff(
            oldText = "line1\nline2",
            newText = "line1\nline2\nline3",
            path = "file.txt",
        )!!
        assertEquals(listOf("+line3"), diff.lines().filter { it.startsWith("+") && !it.startsWith("+++") })
    }

    @Test
    fun `splits distant changes into separate hunks`() {
        val oldLines = (1..20).map { "line$it" }
        val newLines = oldLines.toMutableList().also {
            it[0] = "changed1"
            it[19] = "changed20"
        }
        val diff = generateUnifiedDiff(
            oldText = oldLines.joinToString("\n"),
            newText = newLines.joinToString("\n"),
            path = "file.txt",
        )!!
        assertEquals(2, diff.lines().count { it.startsWith("@@") })
    }

    @Test
    fun `merges nearby changes into one hunk`() {
        val oldLines = (1..10).map { "line$it" }
        val newLines = oldLines.toMutableList().also {
            it[2] = "changed3"
            it[5] = "changed6"
        }
        val diff = generateUnifiedDiff(
            oldText = oldLines.joinToString("\n"),
            newText = newLines.joinToString("\n"),
            path = "file.txt",
        )!!
        assertEquals(1, diff.lines().count { it.startsWith("@@") })
    }

    @Test
    fun `handles replace all style multiple replacements`() {
        val diff = generateUnifiedDiff(
            oldText = "foo\nbar\nfoo",
            newText = "baz\nbar\nbaz",
            path = "file.txt",
        )!!
        assertEquals(
            listOf("-foo", "-foo"),
            diff.lines().filter { it.startsWith("-") && !it.startsWith("---") },
        )
        assertEquals(
            listOf("+baz", "+baz"),
            diff.lines().filter { it.startsWith("+") && !it.startsWith("+++") },
        )
    }

    // ---- boundary cases the file-edit tool actually hits: a brand-new file, a fully-cleared file,
    // and a pure deletion (no additions). Codex flagged these as uncovered. ----

    @Test
    fun `generates diff for empty old (new file)`() {
        val diff = generateUnifiedDiff(oldText = "", newText = "alpha\nbeta", path = "file.txt")
        assertNotNull("an empty->non-empty diff must not be null", diff)
        val added = diff!!.lines().filter { it.startsWith("+") && !it.startsWith("+++") }
        assertTrue("the new content must appear as additions", added.contains("+alpha") && added.contains("+beta"))
    }

    @Test
    fun `generates diff for empty new (cleared file)`() {
        val diff = generateUnifiedDiff(oldText = "alpha\nbeta\ngamma", newText = "", path = "file.txt")
        assertNotNull("a non-empty->empty diff must not be null", diff)
        val removed = diff!!.lines().filter { it.startsWith("-") && !it.startsWith("---") }
        assertTrue(
            "all old lines must appear as deletions",
            removed.contains("-alpha") && removed.contains("-beta") && removed.contains("-gamma"),
        )
    }

    @Test
    fun `generates deletion-only diff with no additions`() {
        val diff = generateUnifiedDiff(oldText = "keep1\ndrop\nkeep2", newText = "keep1\nkeep2", path = "file.txt")!!
        assertEquals(listOf("-drop"), diff.lines().filter { it.startsWith("-") && !it.startsWith("---") })
        assertTrue(
            "a pure deletion must add nothing",
            diff.lines().none { it.startsWith("+") && !it.startsWith("+++") },
        )
    }
}
