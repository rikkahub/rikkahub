package me.rerere.highlight.kotlin.languages.cmake

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.cmake.rules.CMakeBracketRule
import me.rerere.highlight.kotlin.languages.cmake.rules.CMakeIdentifierRule
import me.rerere.highlight.kotlin.languages.cmake.rules.CMakeStringRule
import me.rerere.highlight.kotlin.languages.cmake.rules.CMakeVariableRule

internal fun createCMakeRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        CMakeBracketRule,
        DelimitedRule(
            startDelimiter = "#",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
        ),
        CMakeStringRule,
        CMakeVariableRule,
        RegexRule(
            pattern = Regex("""\b[0-9]+(?:\.[0-9]+)*\b"""),
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        CMakeIdentifierRule,
        RegexRule(
            pattern = Regex("""[()]"""),
            scope = TokenScope.PUNCTUATION,
            nextKind = LexemeKind.OpeningDelimiter,
        ),
    )
}
