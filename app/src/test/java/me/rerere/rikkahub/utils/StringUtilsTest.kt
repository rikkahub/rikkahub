package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringUtilsTest {

    @Test
    fun `extract chinese double quotes`() {
        assertEquals(listOf("你好"), "他说“你好”".extractQuotedContent())
    }

    @Test
    fun `extract chinese single quotes`() {
        assertEquals(listOf("世界"), "标题是‘世界’".extractQuotedContent())
    }

    @Test
    fun `extract english double quotes`() {
        assertEquals(listOf("hello"), "he said \"hello\"".extractQuotedContent())
    }

    @Test
    fun `extract english single quotes`() {
        assertEquals(listOf("world"), "title is 'world'".extractQuotedContent())
    }

    @Test
    fun `extract corner brackets`() {
        assertEquals(listOf("你好"), "他说「你好」".extractQuotedContent())
    }

    @Test
    fun `extract white corner brackets`() {
        assertEquals(listOf("世界"), "标题是『世界』".extractQuotedContent())
    }

    @Test
    fun `extract multiple quotes`() {
        assertEquals(
            listOf("你好", "世界"),
            "“你好” 和 ‘世界’".extractQuotedContent(),
        )
    }

    @Test
    fun `blank content is ignored`() {
        assertTrue("“” \"\" '  '".extractQuotedContent().isEmpty())
    }

    @Test
    fun `no quotes returns empty`() {
        assertTrue("没有任何引号".extractQuotedContent().isEmpty())
    }

    @Test
    fun `extract as text joins with separator`() {
        assertEquals("你好\n世界", "“你好”‘世界’".extractQuotedContentAsText())
    }

    @Test
    fun `extract as text returns null when empty`() {
        assertNull("没有引号".extractQuotedContentAsText())
    }

    @Test
    fun `remove english brackets`() {
        assertEquals("你好世界", "你好(旁白)世界".removeBracketedContent())
    }

    @Test
    fun `remove chinese brackets`() {
        assertEquals("你好世界", "你好（旁白）世界".removeBracketedContent())
    }

    @Test
    fun `remove multiple brackets`() {
        assertEquals("你好世界", "你好(注释)世界（备注）".removeBracketedContent())
    }

    @Test
    fun `remove brackets keeps outside text trimmed`() {
        assertEquals("你好", "(旁白) 你好 ".removeBracketedContent())
    }

    @Test
    fun `remove brackets does not cross bracket boundaries`() {
        assertEquals("ac", "a(b)c".removeBracketedContent())
    }

    @Test
    fun `remove brackets returns null when all removed`() {
        assertNull("(全是旁白)".removeBracketedContent())
    }

    @Test
    fun `remove brackets returns null for blank result`() {
        assertNull("（旁白） ".removeBracketedContent())
    }

    @Test
    fun `no brackets returns original text`() {
        assertEquals("没有括号", "没有括号".removeBracketedContent())
    }

    @Test
    fun `tts filter returns empty for bracket-only text`() {
        assertEquals(
            "",
            "（舞台指示）".filterTextForTts(
                onlyReadQuoted = false,
                onlyReadOutsideBrackets = true,
            ),
        )
    }

    @Test
    fun `tts filter strips brackets and keeps ordinary text`() {
        assertEquals(
            "你好世界",
            "你好(低声)世界".filterTextForTts(
                onlyReadQuoted = false,
                onlyReadOutsideBrackets = true,
            ),
        )
    }

    @Test
    fun `tts filter composes quoted extraction before bracket removal`() {
        assertEquals(
            "你好",
            "他说“你好（低声）”".filterTextForTts(
                onlyReadQuoted = true,
                onlyReadOutsideBrackets = true,
            ),
        )
    }

    @Test
    fun `tts filter leaves text unchanged when filters are disabled`() {
        val text = "  原文（旁白）  "

        assertEquals(
            text,
            text.filterTextForTts(
                onlyReadQuoted = false,
                onlyReadOutsideBrackets = false,
            ),
        )
    }

    @Test
    fun `tts quoted-only filter falls back to original text without quotes`() {
        val text = "没有引号"

        assertEquals(
            text,
            text.filterTextForTts(
                onlyReadQuoted = true,
                onlyReadOutsideBrackets = false,
            ),
        )
    }
}
