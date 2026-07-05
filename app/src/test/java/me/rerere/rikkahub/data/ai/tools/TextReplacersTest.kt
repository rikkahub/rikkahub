package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextReplacersTest {

    // ---- exact ----

    @Test
    fun `exact match replaces single occurrence`() {
        val result = replaceText("hello world", "world", "kotlin", replaceAll = false)
        assertEquals("hello kotlin", result.updated)
        assertEquals(1, result.replacements)
        assertEquals("exact", result.strategy)
    }

    @Test
    fun `exact match counts non overlapping occurrences`() {
        // "aaa" 中 "aa" 非重叠只出现 1 次, 不应被误判为多处
        val result = replaceText("aaa", "aa", "b", replaceAll = false)
        assertEquals("ba", result.updated)
        assertEquals(1, result.replacements)
    }

    @Test
    fun `exact match with replace_all replaces every occurrence`() {
        val result = replaceText("foo bar foo baz foo", "foo", "qux", replaceAll = true)
        assertEquals("qux bar qux baz qux", result.updated)
        assertEquals(3, result.replacements)
    }

    @Test
    fun `exact match throws on ambiguous occurrences without replace_all`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            replaceText("foo foo", "foo", "bar", replaceAll = false)
        }
        assertEquals(true, e.message!!.contains("2 locations"))
    }

    @Test
    fun `throws when old_text is not found anywhere`() {
        assertThrows(IllegalArgumentException::class.java) {
            replaceText("hello world", "missing", "x", replaceAll = false)
        }
    }

    @Test
    fun `throws when old_text is empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            replaceText("hello", "", "x", replaceAll = false)
        }
    }

    // ---- line_trimmed ----

    @Test
    fun `line trimmed matches despite indentation mismatch and reindents new text`() {
        val content = "fun main() {\n    println(\"a\")\n    println(\"b\")\n}"
        // 模型给的 old_text 丢失了缩进
        val result = replaceText(
            content = content,
            oldText = "println(\"a\")\nprintln(\"b\")",
            newText = "println(\"c\")",
            replaceAll = false,
        )
        assertEquals("fun main() {\n    println(\"c\")\n}", result.updated)
        assertEquals("line_trimmed", result.strategy)
    }

    @Test
    fun `line trimmed reindent preserves relative indentation of new text`() {
        val content = "class A {\n    fun f() {\n        old()\n    }\n}"
        val result = replaceText(
            content = content,
            oldText = "fun f() {\n    old()\n}",
            newText = "fun f() {\n    new()\n}",
            replaceAll = false,
        )
        assertEquals("class A {\n    fun f() {\n        new()\n    }\n}", result.updated)
        assertEquals("line_trimmed", result.strategy)
    }

    @Test
    fun `line trimmed matches crlf content with lf old_text`() {
        val content = "line1\r\nline2\r\nline3"
        val result = replaceText(content, "line1\nline2", "replaced", replaceAll = false)
        assertEquals("replaced\r\nline3", result.updated)
        assertEquals("line_trimmed", result.strategy)
    }

    @Test
    fun `line trimmed handles trailing newline in old_text`() {
        val content = "\tfoo\r\nbar"
        val result = replaceText(content, "foo\n", "baz\n", replaceAll = false)
        assertEquals("\tbaz\r\nbar", result.updated)
        assertEquals("line_trimmed", result.strategy)
    }

    @Test
    fun `line trimmed throws on ambiguous match without replace_all`() {
        val content = "    foo\nbar\n    foo"
        // exact 找不到 tab 缩进的 "foo", line_trimmed 命中两处
        val e = assertThrows(IllegalArgumentException::class.java) {
            replaceText(content, "\tfoo", "baz", replaceAll = false)
        }
        assertEquals(true, e.message!!.contains("line_trimmed"))
    }

    @Test
    fun `whitespace only old_text never fuzzy matches`() {
        assertThrows(IllegalArgumentException::class.java) {
            replaceText("a\n\n\nb", "    \n  ", "x", replaceAll = false)
        }
    }

    // ---- block_anchor ----

    @Test
    fun `block anchor matches when middle lines differ slightly`() {
        val content = "fun calc() {\n    val x = a + b\n    return x\n}"
        // 中间行的空格写错了, line_trimmed 失败, 首尾行锚点命中
        val result = replaceText(
            content = content,
            oldText = "fun calc() {\n    val x = a+b\n    return x\n}",
            newText = "fun calc() = a + b",
            replaceAll = false,
        )
        assertEquals("fun calc() = a + b", result.updated)
        assertEquals("block_anchor", result.strategy)
    }

    @Test
    fun `block anchor requires at least three lines`() {
        // 两行的 old_text 中间行写错时不应启用锚点匹配
        assertThrows(IllegalArgumentException::class.java) {
            replaceText("start\nend", "start oops\nend", "x", replaceAll = false)
        }
    }
}
