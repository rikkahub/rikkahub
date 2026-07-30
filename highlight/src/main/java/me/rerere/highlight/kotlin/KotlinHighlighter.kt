package me.rerere.highlight.kotlin

import me.rerere.highlight.HighlightToken
import me.rerere.highlight.kotlin.engine.GrammarEngine
import me.rerere.highlight.kotlin.languages.bash.BashLanguage
import me.rerere.highlight.kotlin.languages.css.CssLanguage
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptLanguage
import me.rerere.highlight.kotlin.languages.javascript.TypeScriptLanguage
import me.rerere.highlight.kotlin.languages.json.JsonLanguage
import me.rerere.highlight.kotlin.languages.sql.SqlLanguage
import me.rerere.highlight.kotlin.languages.toml.TomlLanguage

/**
 * A pure Kotlin syntax highlighter
 *
 * The grammar and matching order are based on highlight.js 11.11.2
*/
class KotlinHighlighter {
    private val engine = GrammarEngine(
        languages = listOf(
            JavaScriptLanguage.definition,
            TypeScriptLanguage.definition,
            JsonLanguage.definition,
            BashLanguage.definition,
            TomlLanguage.definition,
            SqlLanguage.definition,
            CssLanguage.definition,
        ),
    )

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()

        return engine.highlight(code, language)
            ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean = engine.supports(language)
}
