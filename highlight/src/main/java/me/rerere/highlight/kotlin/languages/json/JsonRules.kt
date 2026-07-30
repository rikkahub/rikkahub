package me.rerere.highlight.kotlin.languages.json

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule

internal fun createJsonRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        DelimitedRule(
            startDelimiter = "//",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
        ),
        DelimitedRule(
            startDelimiter = "/*",
            endDelimiter = "*/",
            scope = TokenScope.COMMENT,
        ),
        RegexRule(
            pattern = JsonGrammar.PROPERTY_PATTERN,
            scope = TokenScope.PROPERTY,
            nextKind = LexemeKind.Value,
        ),
        DelimitedRule(
            startDelimiter = "\"",
            endDelimiter = "\"",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
            stopAtLineBreak = true,
        ),
        DelimitedRule(
            startDelimiter = "'",
            endDelimiter = "'",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
            stopAtLineBreak = true,
        ),
        RegexRule(
            pattern = JsonGrammar.NUMBER_PATTERN,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""\b(?:true|false)\b"""),
            scope = TokenScope.BOOLEAN,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""\b(?:null|Infinity|NaN)\b"""),
            scope = TokenScope.CONSTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""[{}\[\],:]"""),
            scope = TokenScope.PUNCTUATION,
        ),
    )
}
