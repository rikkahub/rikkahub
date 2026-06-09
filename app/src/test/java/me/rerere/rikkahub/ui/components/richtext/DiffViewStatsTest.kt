package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffViewStatsTest {

    @Test
    fun `counts added and removed body lines ignoring file headers`() {
        val diff = """
            --- a/file.txt
            +++ b/file.txt
            @@ -1,3 +1,4 @@
             context
            -removed
            +added one
            +added two
        """.trimIndent()

        assertEquals(DiffStats(additions = 2, deletions = 1), parseDiffStats(diff))
    }

    @Test
    fun `returns zero stats for an empty diff`() {
        assertEquals(DiffStats(additions = 0, deletions = 0), parseDiffStats(""))
    }
}
