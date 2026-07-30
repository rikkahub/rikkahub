package me.rerere.highlight.kotlin.languages.c

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class CHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights C preprocessors declarations and calls`() {
        val code = """
            #include <stdio.h>
            #define CAPACITY 32

            struct Buffer {
                size_t length;
            };

            int main(void) {
                printf("capacity=%d\n", CAPACITY);
                return 0;
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "c")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("c"))
        assertTrue(highlighter.supports("h"))
        assertToken(tokens, "#include", "important")
        assertToken(tokens, "<stdio.h>", "string")
        assertToken(tokens, "#define", "important")
        assertToken(tokens, "CAPACITY", "constant")
        assertToken(tokens, "struct", "keyword")
        assertToken(tokens, "Buffer", "class-name")
        assertToken(tokens, "size_t", "class-name")
        assertToken(tokens, "int", "class-name")
        assertToken(tokens, "main", "function")
        assertToken(tokens, "printf", "function")
    }

    @Test
    fun `highlights C literals comments and member access`() {
        val code = """
            // continued comment \
            still a comment
            /* block comment */
            struct Node *node = NULL;
            double ratio = 0x1.fp+2;
            unsigned mask = 0b1010u;
            node->value += 1'000;
        """.trimIndent()

        val tokens = highlighter.highlight(code, "c")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "// continued comment \\\nstill a comment", "comment")
        assertToken(tokens, "/* block comment */", "comment")
        assertToken(tokens, "Node", "class-name")
        assertToken(tokens, "NULL", "constant")
        assertToken(tokens, "0x1.fp+2", "number")
        assertToken(tokens, "0b1010u", "number")
        assertToken(tokens, "->", "operator")
        assertToken(tokens, "value", "property")
        assertToken(tokens, "1'000", "number")
    }

    @Test
    fun `highlights continued C preprocessor expressions`() {
        val code = """
            #define MAX_OF(a, b) \
                ((a) > (b) ? (a) : (b))
            bool enabled = true;
        """.trimIndent()

        val tokens = highlighter.highlight(code, "c")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "#define", "important")
        assertToken(tokens, "MAX_OF", "function")
        assertToken(tokens, ">", "operator")
        assertToken(tokens, "bool", "class-name")
        assertToken(tokens, "true", "boolean")
    }

    @Test
    fun `preserves incomplete C constructs`() {
        val samples = listOf(
            """const char *value = "unfinished""",
            "/* unfinished",
            "#include <unfinished",
            "struct Example {",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "c"))
        }
    }
}
