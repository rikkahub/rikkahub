package me.rerere.highlight.kotlin.languages.toml

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.toml.rules.TomlKeyRule
import me.rerere.highlight.kotlin.languages.toml.rules.TomlTableRule

internal fun createTomlRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        DelimitedRule(
            startDelimiter = "#",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
        ),
        TomlTableRule,
        TomlKeyRule,
        DelimitedRule(
            startDelimiter = "\"\"\"",
            endDelimiter = "\"\"\"",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
        ),
        DelimitedRule(
            startDelimiter = "'''",
            endDelimiter = "'''",
            scope = TokenScope.STRING,
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
            stopAtLineBreak = true,
        ),
        RegexRule(
            pattern = TomlGrammar.DATE_TIME_PATTERN,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""\b(?:true|false)\b"""),
            scope = TokenScope.BOOLEAN,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = TomlGrammar.NUMBER_PATTERN,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""="""),
            scope = TokenScope.OPERATOR,
            nextKind = LexemeKind.Operator,
        ),
        RegexRule(
            pattern = Regex("""[\[\]{},.]"""),
            scope = TokenScope.PUNCTUATION,
        ),
    )
}
