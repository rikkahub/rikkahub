package me.rerere.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import me.rerere.ui.markdown.MarkdownBlock.CodeBlock
import me.rerere.ui.markdown.MarkdownBlock.Container
import me.rerere.ui.markdown.MarkdownBlock.Details
import me.rerere.ui.markdown.MarkdownBlock.Heading
import me.rerere.ui.markdown.MarkdownBlock.ListBlock
import me.rerere.ui.markdown.MarkdownBlock.Paragraph
import me.rerere.ui.markdown.MarkdownBlock.Quote
import me.rerere.ui.markdown.MarkdownBlock.Table
import me.rerere.ui.markdown.MarkdownInline.Code
import me.rerere.ui.markdown.MarkdownInline.Image
import me.rerere.ui.markdown.MarkdownInline.Link
import me.rerere.ui.markdown.MarkdownInline.Math
import me.rerere.ui.markdown.MarkdownInline.Styled
import me.rerere.ui.markdown.MarkdownInline.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun blocks(markdown: String, softBreakAsLineBreak: Boolean = true) =
        parseMarkdown(markdown.trimIndent(), softBreakAsLineBreak).blocks

    private inline fun <reified T : MarkdownBlock> single(markdown: String): T {
        val blocks = blocks(markdown)
        assertEquals("expected a single block but got $blocks", 1, blocks.size)
        return blocks.single() as T
    }

    @Test
    fun `paragraph with inline styles`() {
        val paragraph = single<Paragraph>("Hello **bold** and *italic* and ~~gone~~ `code`")
        assertEquals("Hello bold and italic and gone code", paragraph.content.plainText())

        val (bold, italic, strike) = paragraph.content.filterIsInstance<Styled>()
        assertEquals(FontWeight.Bold, bold.style.fontWeight)
        assertEquals(listOf(Text("italic")), italic.children)
        assertEquals(TextDecoration.LineThrough, strike.style.textDecoration)
        assertEquals(Code("code"), paragraph.content.last())
    }

    @Test
    fun `soft break follows the option`() {
        val source = """
            first
            second
        """
        assertEquals(listOf(Paragraph(listOf(Text("first\nsecond")))), blocks(source))
        assertEquals(listOf(Paragraph(listOf(Text("first second")))), blocks(source, softBreakAsLineBreak = false))
    }

    @Test
    fun `line break does not produce an empty line`() {
        assertEquals(listOf(Text("first\nsecond")), single<Paragraph>("first  \nsecond").content)
        assertEquals(listOf(Text("ha\nha")), single<Paragraph>("ha<br/>ha").content)
    }

    @Test
    fun `headings keep their level`() {
        val blocks = blocks(
            """
            # One
            ### Three
            """
        )
        assertEquals(listOf(Heading(1, listOf(Text("One"))), Heading(3, listOf(Text("Three")))), blocks)
    }

    @Test
    fun `code fence reports language and whether it is closed`() {
        val closed = single<CodeBlock>(
            """
            ```kotlin
            val a = 1 < 2
            ```
            """
        )
        assertEquals(CodeBlock(code = "val a = 1 < 2", language = "kotlin", closed = true), closed)

        val streaming = single<CodeBlock>(
            """
            ```html
            <div>
            """
        )
        assertEquals(CodeBlock(code = "<div>", language = "html", closed = false), streaming)
    }

    @Test
    fun `code fences close with CRLF line endings`() {
        val source = "````\n文字 A\n\n````\n文字 B\n```\n\n文字 C\n```".replace("\n", "\r\n")
        assertEquals(
            listOf(
                CodeBlock(code = "文字 A", language = null, closed = true),
                Paragraph(listOf(Text("文字 B"))),
                CodeBlock(code = "\n文字 C", language = null, closed = true),
            ),
            parseMarkdown(source).blocks,
        )
    }

    @Test
    fun `lists keep start number nesting and task state`() {
        val list = single<ListBlock>(
            """
            3. three
            4. four
               - [x] done
               - [ ] todo
            """
        )
        assertTrue(list.ordered)
        assertTrue(list.tight)
        assertEquals(3, list.start)
        assertEquals(listOf(Paragraph(listOf(Text("three")))), list.items[0].children)
        assertNull(list.items[0].checked)

        val (text, nested) = list.items[1].children
        assertEquals(Paragraph(listOf(Text("four"))), text)
        nested as ListBlock
        assertFalse(nested.ordered)
        assertEquals(listOf(true, false), nested.items.map { it.checked })
        assertEquals(listOf(Paragraph(listOf(Text("done")))), nested.items[0].children)
    }

    @Test
    fun `list separated by blank lines is loose`() {
        val list = single<ListBlock>(
            """
            - one

            - two
            """
        )
        assertFalse(list.tight)
        assertEquals(listOf(Paragraph(listOf(Text("two")))), list.items[1].children)
    }

    @Test
    fun `table keeps cells alignment and source`() {
        val source = """
            | a | b |
            |:--|:-:|
            | 1 | **2** |
        """.trimIndent()
        val table = single<Table>(source)
        assertEquals(listOf("a", "b"), table.header.map { it.content.plainText() })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.content.plainText() } })
        assertEquals(listOf(null, TextAlign.Center), table.alignments)
        assertEquals(source, table.source)
    }

    @Test
    fun `html table cells keep their own style`() {
        val table = single<Table>(
            """
            <table style="font-size: 12px">
              <tr><th align="center">状态</th><th>数量</th></tr>
              <tr align="right"><td style="color: red; font-weight: bold">异常</td><td style="text-align: left">3</td></tr>
              <tr><td>正常</td><td>5</td></tr>
            </table>
            """
        )
        assertEquals(listOf(TextAlign.Center, null), table.alignments)
        assertNull(table.source)

        val (error, count) = table.rows[0]
        assertEquals("异常", error.content.plainText())
        assertEquals(Color.Red, error.style!!.color)
        assertEquals(FontWeight.Bold, error.style!!.fontWeight)
        // 行上的对齐由单元格继承，单元格自己写了的以自己为准
        assertEquals(TextAlign.End, error.style!!.textAlign)
        assertEquals(TextAlign.Start, count.style!!.textAlign)
        // 表格上的样式一直传到没有自己样式的单元格
        assertEquals(12f, table.rows[1][0].style!!.fontSize.value)
        assertEquals(TextAlign.Unspecified, table.rows[1][0].style!!.textAlign)
    }

    @Test
    fun `markdown table cells without alignment have no style`() {
        val table = single<Table>(
            """
            | a | b |
            |---|--:|
            | 1 | 2 |
            """
        )
        assertEquals(listOf(null, TextAlign.End), table.alignments)
        assertNull(table.header[0].style)
        assertNull(table.rows[0][0].style)
    }

    @Test
    fun `quote contains blocks`() {
        val quote = single<Quote>(
            """
            > quoted
            > text
            """
        )
        assertEquals(listOf(Paragraph(listOf(Text("quoted\ntext")))), quote.children)
    }

    @Test
    fun `links images and math`() {
        val paragraph = single<Paragraph>("""[text](https://a.com "title") ![alt](https://i.png) ${'$'}x^2$""")
        assertEquals(
            listOf(
                Link("https://a.com", listOf(Text("text")), "title"),
                Text(" "),
                Image("https://i.png", alt = "alt"),
                Text(" "),
                Math("x^2", display = false),
            ),
            paragraph.content,
        )
    }

    @Test
    fun `latex delimiters are normalized outside of code`() {
        assertEquals(listOf(Math("a+b")), single<Paragraph>("""\(a+b\)""").content)
        assertEquals(listOf(Math("a + b")), single<Paragraph>("""\( a + b \)""").content)
        assertEquals(
            listOf(Math("a = b", display = true)),
            single<Paragraph>(
                """
                \[
                a = b
                \]
                """
            ).content,
        )
        assertEquals(listOf(Code("""\(a\)""")), single<Paragraph>("""`\(a\)`""").content)
        // 未闭合的代码围栏里也不能替换
        assertEquals("""\[a\]""", single<CodeBlock>("```\n\\[a\\]").code)
    }

    @Test
    fun `inline html wraps markdown content`() {
        val paragraph = single<Paragraph>("""<span style="color: #f00; font-size: 20px">**red**</span> text""")
        val span = paragraph.content.first() as Styled
        assertEquals(Color.Red, span.style.color)
        assertEquals(20f, span.style.fontSize.value)
        assertEquals(FontWeight.Bold, (span.children.single() as Styled).style.fontWeight)
        assertEquals(Text(" text"), paragraph.content.last())
    }

    @Test
    fun `html block split by blank lines is joined back`() {
        val details = single<Details>(
            """
            <details>
            <summary>Sum</summary>

            inner **md**

            </details>
            """
        )
        assertEquals("Sum", details.summary.plainText())
        assertFalse(details.open)
        assertEquals("inner md", (details.children.single() as Paragraph).content.plainText())
    }

    @Test
    fun `html container carries block style`() {
        val container = single<Container>(
            """
            <div align="center">
              <b>Title</b>
              <i>sub</i>
            </div>
            """
        )
        assertEquals(TextAlign.Center, container.style.textAlign)
        // 手写 HTML 里的缩进和换行折叠成空格，不产生换行
        assertEquals("Title sub", (container.children.single() as Paragraph).content.plainText())
    }

    @Test
    fun `unknown inline tags stay as text`() {
        assertEquals(
            listOf(Text("a List<String> and <answer>42</answer>")),
            single<Paragraph>("a List<String> and <answer>42</answer>").content,
        )
    }

    @Test
    fun `script and style content is dropped`() {
        assertEquals(
            listOf(Paragraph(listOf(Text("visible")))),
            blocks(
                """
                <style>p { color: red }</style>

                visible
                """
            ),
        )
    }

    @Test
    fun `paragraph is split around images and display math`() {
        val content = single<Paragraph>("before [![badge](https://i.png)](https://a.com) after").content
        assertEquals(
            listOf(
                InlineSegment.Text(listOf(Text("before "))),
                InlineSegment.Image(Image("https://i.png", alt = "badge"), link = "https://a.com"),
                InlineSegment.Text(listOf(Text(" after"))),
            ),
            content.toSegments(),
        )

        val plain = listOf(Text("plain"))
        assertEquals(listOf(InlineSegment.Text(plain)), plain.toSegments())
    }

    @Test
    fun `css values`() {
        assertEquals(Color(0x80, 0x00, 0xFF, 0x80), parseCssColor("rgba(128, 0, 255, 0.5)"))
        assertEquals(Color(0x11, 0x22, 0x33, 0xFF), parseCssColor("#123"))
        assertEquals(Color(0xFF008000), parseCssColor("Green"))
        assertNull(parseCssColor("#12"))
        assertNull(parseSpanStyle("unknown: 1"))
        assertEquals(1.5f, parseSpanStyle("font-size: 150%")!!.fontSize.value)
    }
}
