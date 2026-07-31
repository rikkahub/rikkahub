package me.rerere.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public entry point.
 *
 * Per language behaviour is pinned against `highlight.js` by the fixture tests; this covers the
 * surface the app touches.
 */
class CodeHighlighterTest {
    private val highlighter = CodeHighlighter()

    @Test
    fun `supports the aliases of every bundled language`() {
        listOf(
            "json", "jsonc", "json5",
            "toml", "ini",
            "cmake", "cmake.in",
            "go", "golang",
            "yaml", "yml",
            "bash", "sh", "zsh", "shell",
            "dockerfile", "docker",
            "javascript", "js", "jsx", "mjs", "cjs",
            "typescript", "ts", "tsx", "mts", "cts",
            "xml", "html", "xhtml", "rss", "atom", "xjb", "xsd", "xsl", "plist", "wsf", "svg",
            "css",
            "java", "jsp",
            "kotlin", "kt", "kts",
            "python", "py", "gyp", "ipython",
            "c", "h",
            "cpp", "cc", "c++", "h++", "hpp", "hh", "hxx", "cxx",
            "sql",
            "diff", "patch",
            "markdown", "md", "mkdown", "mkd",
            "rust", "rs",
        ).forEach { assertTrue(it, highlighter.supports(it)) }
    }

    @Test
    fun `resolves an alias regardless of case and padding`() {
        assertTrue(highlighter.supports(" GoLang "))
        assertToken(highlighter.highlight("package main", " GoLang "), "package", "keyword")
    }

    @Test
    fun `emits the highlight_js scope vocabulary`() {
        val tokens = highlighter.highlight("""{"a": true}""", "json")

        assertToken(tokens, "\"a\"", "attr")
        assertToken(tokens, "{", "punctuation")
        // The literal mode opens a `literal` scope but its keywords emit a nested `keyword` one,
        // and the innermost scope is the one a token ends up with.
        assertToken(tokens, "true", "keyword")
    }

    @Test
    fun `an unsupported language stays plain text`() {
        val code = "print(\"hi\")"
        val language = "not-a-language"

        assertFalse(highlighter.supports(language))
        assertEquals(listOf(HighlightToken.Plain(code)), highlighter.highlight(code, language))
    }

    @Test
    fun `yaml erb uses the bundled ruby grammar`() {
        val tokens = highlighter.highlight("template: <% if user %><%= user.name %><% end %>", "yaml")

        assertToken(tokens, "if", "keyword")
        assertToken(tokens, "end", "keyword")
    }

    @Test
    fun `empty input yields no tokens`() {
        assertTrue(highlighter.highlight("", "json").isEmpty())
    }

    private fun assertToken(tokens: List<HighlightToken>, content: String, type: String) {
        assertTrue(
            "expected token '$content' of type '$type', got $tokens",
            tokens.any { it is HighlightToken.Styled && it.content == content && it.type == type },
        )
    }
}
