package me.rerere.highlight.core

import me.rerere.highlight.HighlightToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the mode stack engine itself, exercised through small purpose built grammars.
 *
 * Real grammars are covered by the `highlight.js` fixtures; these cases pin the individual mode
 * features that a fixture only reaches indirectly.
 */
class HighlightEngineTest {

    @Test
    fun `applies keywords to the text between matches`() {
        val tokens = highlight("let x = 1", language("demo") {
            keywords = keywords { keyword("let"); literal("true false") }
            contains = listOf(NUMBER_MODE)
        })

        assertEquals("keyword:let| x = |number:1", tokens.describe())
    }

    @Test
    fun `nests modes and lets the innermost scope win`() {
        val tokens = highlight(""""a ${'$'}{b} c"""", language("demo") {
            contains = listOf(
                mode {
                    scope = "string"
                    begin = "\""
                    end = "\""
                    contains = listOf(
                        mode {
                            scope = "subst"
                            begin = """\$\{"""
                            end = """\}"""
                        },
                    )
                },
            )
        })

        assertEquals("""string:"a |subst:${'$'}{b}|string: c"""", tokens.describe())
    }

    @Test
    fun `excludeBegin and excludeEnd keep delimiters out of the scope`() {
        val tokens = highlight("<<body>>", language("demo") {
            contains = listOf(
                mode {
                    scope = "meta"
                    begin = "<<"
                    end = ">>"
                    excludeBegin = true
                    excludeEnd = true
                },
            )
        })

        assertEquals("<<|meta:body|>>", tokens.describe())
    }

    @Test
    fun `endsParent closes the enclosing mode as well`() {
        val tokens = highlight("f(a) tail", language("demo") {
            contains = listOf(
                mode {
                    scope = "function"
                    begin = "f"
                    end = """\s"""
                    contains = listOf(
                        mode {
                            scope = "params"
                            begin = """\("""
                            end = """\)"""
                            endsParent = true
                        },
                    )
                },
            )
        })

        // The params mode ends its parent, so `tail` is no longer inside the function scope.
        assertEquals("function:f|params:(a)| tail", tokens.describe())
    }

    @Test
    fun `starts hands over to a following mode`() {
        val tokens = highlight("RUN echo hi", language("demo") {
            contains = listOf(
                mode {
                    scope = "keyword"
                    begin = "RUN"
                    starts = mode {
                        scope = "string"
                        end = "$"
                    }
                },
            )
        })

        assertEquals("keyword:RUN|string: echo hi", tokens.describe())
    }

    @Test
    fun `beginKeywords ignores a match preceded by a dot`() {
        val definition = language("demo") {
            contains = listOf(
                mode {
                    scope = "keyword"
                    beginKeywords = "class"
                },
            )
        }

        assertEquals("keyword:class| Foo", highlight("class Foo", definition).describe())
        assertEquals("bob.class Foo", highlight("bob.class Foo", definition).describe())
    }

    @Test
    fun `match arrays scope each capture group on their own`() {
        val tokens = highlight("def name", language("demo") {
            contains = listOf(
                mode {
                    matchList = listOf("""\bdef""", """\s+""", """\w+""")
                    scopes = mapOf(1 to "keyword", 3 to "title")
                },
            )
        })

        assertEquals("keyword:def| |title:name", tokens.describe())
    }

    @Test
    fun `an unknown sub-language degrades to plain text`() {
        val tokens = highlight("<%code%>", language("demo") {
            contains = listOf(
                mode {
                    begin = "<%"
                    end = "%>"
                    subLanguage = "nonexistent"
                    excludeBegin = true
                    excludeEnd = true
                },
            )
        })

        assertEquals("<%code%>", tokens.describe())
    }

    @Test
    fun `a known sub-language is highlighted by its own grammar`() {
        val host = language("host") {
            contains = listOf(
                mode {
                    begin = "<%"
                    end = "%>"
                    subLanguage = "guest"
                    excludeBegin = true
                    excludeEnd = true
                },
            )
        }
        val guest = language("guest") {
            keywords = keywords("let")
        }
        val engine = HighlightEngine(listOf(host, guest))

        val tokens = engine.highlight("<%let x%>", "host").orEmpty()

        assertEquals("<%|keyword:let| x%>", tokens.describe())
    }

    @Test
    fun `source text always survives highlighting`() {
        val code = "let value = 42 // trailing"
        val tokens = highlight(code, language("demo") {
            keywords = keywords("let")
            contains = listOf(C_LINE_COMMENT_MODE, NUMBER_MODE)
        })

        assertEquals(code, tokens.joinToString(separator = "") { it.content })
    }

    @Test
    fun `resolves aliases and rejects unknown languages`() {
        val engine = HighlightEngine(listOf(language("demo", "d") {}))

        assertTrue(engine.supports("D"))
        assertTrue(engine.supports(" demo "))
        assertFalse(engine.supports("unknown"))
        assertEquals(null, engine.highlight("value", "unknown"))
    }

    private fun language(vararg aliases: String, root: Mode.() -> Unit) = Language(
        name = aliases.first(),
        aliases = aliases.toSet(),
        root = mode(root),
    )

    private fun highlight(code: String, language: Language): List<HighlightToken> {
        highlightDebugMode = true
        try {
            return HighlightEngine(listOf(language)).highlight(code, language.name).orEmpty()
        } finally {
            highlightDebugMode = false
        }
    }

    /** Renders a token list as `scope:text` segments joined by `|`, for readable assertions. */
    private fun List<HighlightToken>.describe(): String = joinToString(separator = "|") { token ->
        when (token) {
            is HighlightToken.Plain -> token.content
            is HighlightToken.Styled -> "${token.type}:${token.content}"
        }
    }
}
