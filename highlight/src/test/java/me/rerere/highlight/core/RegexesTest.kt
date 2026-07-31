package me.rerere.highlight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JavaScript to `java.util.regex` translation grammars depend on. */
class RegexesTest {
    @Test
    fun `translates constructs java rejects`() {
        // `[^]` is "any character" in JavaScript and a syntax error in Java.
        assertTrue(compilePattern("""a[^]b""").matcher("a\nb").matches())
        // `[]` never matches in JavaScript and is a syntax error in Java.
        assertFalse(compilePattern("""[]""").matcher("x").find())
        // `[` and `&` are literal inside a JavaScript character class, but mean nested class union
        // and class intersection in Java.
        assertTrue(compilePattern("""[{}[\],:]""").matcher("[").matches())
        assertTrue(compilePattern("""[a&&b]""").matcher("&").matches())
        // A `{` that opens no quantifier is literal in JavaScript and an error in Java.
        assertTrue(compilePattern("""\$\{|a{""").matcher("a{").find())
        // Real quantifiers must survive untouched.
        assertTrue(compilePattern("""[0-9]{4}(-[0-9][0-9]){0,2}""").matcher("2024-05-27").matches())
    }

    @Test
    fun `unicode mode keeps javascript predefined character class semantics`() {
        val word = compilePattern("""\w""", unicode = true)
        val digit = compilePattern("""\d""", unicode = true)

        assertTrue(word.matcher("a").matches())
        assertFalse(word.matcher("中").matches())
        assertTrue(digit.matcher("1").matches())
        assertFalse(digit.matcher("١").matches())
    }

    @Test
    fun `counts capture groups the way highlight_js does`() {
        assertEquals(0, countMatchGroups("""\s+"""))
        assertEquals(0, countMatchGroups("""(?:a|b)"""))
        assertEquals(1, countMatchGroups("""(a|b)"""))
        assertEquals(3, countMatchGroups("""(a)((b))"""))
        // A bracketed `(` is literal and must not be counted.
        assertEquals(1, countMatchGroups("""[(]\w(x)"""))
        assertEquals(1, countMatchGroups("""(?<name>a)"""))
        assertEquals(0, countMatchGroups("""(?<=a)b"""))
    }

    @Test
    fun `renumbers backreferences when expressions are joined`() {
        val joined = rewriteBackreferences(
            listOf("""(['"]).*?\1""", """(\w)-\1"""),
            joinWith = "|",
        )

        // Each expression gains a wrapping group, so the backreferences shift accordingly.
        assertEquals("""((['"]).*?\2)|((\w)-\4)""", joined)

        val pattern = compilePattern(joined)
        assertTrue(pattern.matcher("""'quoted'""").find())
        assertTrue(pattern.matcher("a-a").find())
        assertFalse(pattern.matcher("a-b").find())
    }

    @Test
    fun `builds alternations and lookaheads`() {
        assertEquals("(?:a|b)", either("a", "b"))
        assertEquals("(a|b)", either("a", "b", capture = true))
        assertEquals("(?=x)", lookahead("x"))
        assertEquals("(?:x)?", optional("x"))
        assertEquals("(?:x)*", anyNumberOfTimes("x"))
        assertEquals("ab", concat("a", "b"))
    }
}
