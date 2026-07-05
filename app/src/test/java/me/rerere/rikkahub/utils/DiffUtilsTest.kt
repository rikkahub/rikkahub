package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
