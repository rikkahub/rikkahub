package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsMatchQueryTest {
    private fun build(keyword: String) = buildFtsMatchQuery(keyword) { "<$it>" }

    @Test
    fun `requires characters of a chinese run to appear near each other`() {
        assertEquals("NEAR(\"你\" \"好\" \"世\" \"界\", 4)", build("你好世界"))
        assertEquals("\"好\"", build("好"))
    }

    @Test
    fun `sends ascii runs to simple_query and joins segments with AND`() {
        assertEquals("<GPT4> AND NEAR(\"模\" \"型\", 2)", build("GPT4模型"))
        assertEquals("NEAR(\"周\" \"杰\" \"伦\", 3) AND <jay>", build("周杰伦 jay"))
    }

    @Test
    fun `escapes quotes and keeps surrogate pairs whole`() {
        assertEquals("NEAR(\"引\" \"\"\"\", 2)", build("引\""))
        assertEquals("NEAR(\"😀\" \"好\", 2)", build("😀好"))
    }

    @Test
    fun `returns null for blank keywords`() {
        assertNull(build("   "))
        assertNull(buildFtsMatchQuery("abc") { "" })
    }
}
