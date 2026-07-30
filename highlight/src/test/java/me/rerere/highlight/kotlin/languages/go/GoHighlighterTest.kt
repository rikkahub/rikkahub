package me.rerere.highlight.kotlin.languages.go

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class GoHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights Go types functions and method receivers`() {
        val code = """
            package main

            type Server struct {
                address string
            }

            func (server *Server) Start(port int) error {
                return nil
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "go")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("go"))
        assertTrue(highlighter.supports("golang"))
        assertToken(tokens, "package", "keyword")
        assertToken(tokens, "type", "keyword")
        assertToken(tokens, "Server", "class-name")
        assertToken(tokens, "struct", "keyword")
        assertToken(tokens, "func", "keyword")
        assertToken(tokens, "Start", "function")
        assertToken(tokens, "string", "class-name")
        assertToken(tokens, "int", "class-name")
        assertToken(tokens, "error", "class-name")
        assertToken(tokens, "nil", "constant")
    }

    @Test
    fun `highlights Go calls properties and built-ins`() {
        val code = """
            func main() {
                values := make([]int, 0, 4)
                values = append(values, 1)
                fmt.Println(len(values))
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "golang")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "main", "function")
        assertToken(tokens, ":=", "operator")
        assertToken(tokens, "make", "function")
        assertToken(tokens, "int", "class-name")
        assertToken(tokens, "append", "function")
        assertToken(tokens, "Println", "function")
        assertToken(tokens, "len", "function")
    }

    @Test
    fun `highlights Go strings comments numbers and literals`() {
        val code = """
            // line comment
            /* block comment */
            const MASK = 0xCA_FE
            ratio := .5e+2
            complexValue := 2.5i
            runeValue := '\n'
            raw := `line\nraw`
            enabled := true
        """.trimIndent()

        val tokens = highlighter.highlight(code, "go")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "// line comment", "comment")
        assertToken(tokens, "/* block comment */", "comment")
        assertToken(tokens, "MASK", "variable")
        assertToken(tokens, "0xCA_FE", "number")
        assertToken(tokens, ".5e+2", "number")
        assertToken(tokens, "2.5i", "number")
        assertToken(tokens, "'\\n'", "string")
        assertToken(tokens, "`line\\nraw`", "string")
        assertToken(tokens, "true", "boolean")
    }

    @Test
    fun `preserves incomplete Go constructs`() {
        val samples = listOf(
            """value := "unfinished""",
            "value := `unfinished",
            "/* unfinished",
            "func (server *Server",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "go"))
        }
    }
}
