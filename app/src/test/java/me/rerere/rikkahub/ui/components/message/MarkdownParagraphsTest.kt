package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParagraphsTest {
    @Test
    fun `splits on blank lines`() {
        val text = "上午去超市。\n\n下午看电影。\n\n\n晚上吃饭。\n"
        assertEquals(listOf("上午去超市。", "下午看电影。", "晚上吃饭。"), splitMarkdownParagraphs(text))
    }

    @Test
    fun `keeps single line breaks inside a paragraph`() {
        assertEquals(listOf("第一行\n第二行", "第三行"), splitMarkdownParagraphs("第一行\n第二行\n\n第三行"))
    }

    @Test
    fun `does not split inside fenced code block`() {
        val code = "```kotlin\nval a = 1\n\nval b = 2\n```"
        assertEquals(listOf("看代码：", code, "完毕"), splitMarkdownParagraphs("看代码：\n\n$code\n\n完毕"))
    }

    @Test
    fun `unclosed fence keeps the rest together while streaming`() {
        val text = "前言\n\n```\na\n\nb"
        assertEquals(listOf("前言", "```\na\n\nb"), splitMarkdownParagraphs(text))
    }

    @Test
    fun `longer fence is not closed by shorter one`() {
        val code = "````\n```\n\n```\n````"
        assertEquals(listOf(code), splitMarkdownParagraphs(code))
    }

    @Test
    fun `does not split inside math block`() {
        val math = "$$\na = b\n\nc = d\n$$"
        assertEquals(listOf("公式", math), splitMarkdownParagraphs("公式\n\n$math"))
    }

    @Test
    fun `indented continuation stays with list item`() {
        val text = "- 第一项\n\n  补充说明\n\n普通段落"
        assertEquals(listOf("- 第一项\n\n  补充说明", "普通段落"), splitMarkdownParagraphs(text))
    }

    @Test
    fun `blank text yields nothing`() {
        assertEquals(emptyList<String>(), splitMarkdownParagraphs("\n \n"))
    }
}
