package me.rerere.highlight.kotlin.languages.yaml

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.yaml.rules.YamlBlockScalarRule
import me.rerere.highlight.kotlin.languages.yaml.rules.YamlKeyRule

internal fun createYamlRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        YamlBlockScalarRule,
        YamlKeyRule,
        RegexRule(
            pattern = Regex("""(?:---|\.\.\.)(?=[ \t]*(?:\r?$|#))""", RegexOption.MULTILINE),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
            condition = { context -> context.isAtLineContentStart() },
        ),
        RegexRule(
            pattern = Regex("""%[A-Za-z]+(?:[ \t]+[^\r\n]*)?"""),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
            condition = { context -> context.isAtLineContentStart() },
        ),
        DelimitedRule(
            startDelimiter = "#",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
            condition = { context ->
                context.index == 0 ||
                    context.source[context.index - 1].isWhitespace() ||
                    context.source[context.index - 1] in "[{,"
            },
        ),
        RegexRule(
            pattern = Regex("""!<[^>\s]+>"""),
            scope = TokenScope.CLASS_NAME,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""!!?[^\s,\[\]{}]+"""),
            scope = TokenScope.CLASS_NAME,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""[&*][A-Za-z_][A-Za-z0-9_-]*"""),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""-(?=[ \t\r\n]|$)"""),
            scope = TokenScope.PUNCTUATION,
            nextKind = LexemeKind.OpeningDelimiter,
        ),
        DelimitedRule(
            startDelimiter = "\"",
            endDelimiter = "\"",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
        ),
        DelimitedRule(
            startDelimiter = "'",
            endDelimiter = "'",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = YamlGrammar.timestampPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""(?i)\b(?:true|false|yes|no)\b"""),
            scope = TokenScope.BOOLEAN,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""(?i)(?:\bnull\b|~)"""),
            scope = TokenScope.CONSTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = YamlGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""[\[\]{},]"""),
            scope = TokenScope.PUNCTUATION,
        ),
        RegexRule(
            pattern = Regex("""[^\s,\[\]{}]+"""),
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        ),
    )
}

private fun me.rerere.highlight.kotlin.engine.MatchContext.isAtLineContentStart(): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && source[cursor] != '\n' && source[cursor] != '\r') {
        if (source[cursor] != ' ' && source[cursor] != '\t') return false
        cursor--
    }
    return true
}
