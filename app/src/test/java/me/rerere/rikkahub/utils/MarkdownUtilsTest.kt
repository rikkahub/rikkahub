package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUtilsTest {

    @Test
    fun `strip html tags but keep text`() {
        assertEquals("标题\n内容", "<details>\n<summary>标题</summary>\n\n内容\n\n</details>".stripMarkdown())
    }

    @Test
    fun `strip inline html tags with attributes`() {
        assertEquals("这是重点文字", "这是<b class=\"x\">重点</b><span style='color:red'>文字</span>".stripMarkdown())
    }

    @Test
    fun `block html tags do not join sentences`() {
        assertEquals("第一行\n第二行", "第一行<br/>第二行".stripMarkdown())
    }

    @Test
    fun `strip html comments and style blocks`() {
        assertEquals("正文", "<!-- 注释 --><style>.a{color:red}</style>正文".stripMarkdown())
    }

    @Test
    fun `decode common html entities`() {
        assertEquals("a < b & c", "a &lt; b &amp; c".stripMarkdown())
    }

    @Test
    fun `keep comparison signs that are not tags`() {
        assertEquals("1 < 2 且 3 > 2", "1 < 2 且 3 > 2".stripMarkdown())
        assertEquals("条件 x<y && y>0", "条件 x<y && y>0".stripMarkdown())
    }

    @Test
    fun `keep email autolink text`() {
        // 解析器不支持邮箱自动链接，界面上原样显示，朗读也保留原文
        assertEquals("联系 <p@example.com>", "联系 <p@example.com>".stripMarkdown())
    }

    @Test
    fun `keep bracket style tts markers`() {
        assertEquals("(开心) 你好 [笑]", "(开心) 你好 [笑]".stripMarkdown())
    }

    @Test
    fun `strip markdown syntax`() {
        assertEquals(
            "标题\n这是重点和链接\n第一项\n第二项\n引用",
            "# 标题\n\n这是**重点**和[链接](https://example.com)\n\n- 第一项\n- 第二项\n\n> 引用".stripMarkdown()
        )
    }

    @Test
    fun `skip code`() {
        assertEquals("运行 即可\n结束", "运行 `ls` 即可\n\n```kotlin\nval a = 1\n```\n\n结束".stripMarkdown())
    }
}
