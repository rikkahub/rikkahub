package me.rerere.highlight.kotlin.languages.bash

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.bash.rules.BashHeredocRule
import me.rerere.highlight.kotlin.languages.bash.rules.BashIdentifierRule
import me.rerere.highlight.kotlin.languages.bash.rules.BashStringRule
import me.rerere.highlight.kotlin.languages.bash.rules.ShellExpansionRule

internal fun createBashRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        RegexRule(
            pattern = Regex("""#![^\r\n]*"""),
            scope = TokenScope.COMMENT,
            nextKind = LexemeKind.Value,
            condition = { it.index == 0 },
        ),
        DelimitedRule(
            startDelimiter = "#",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
            condition = { context ->
                context.index == 0 ||
                    context.source[context.index - 1].isWhitespace() ||
                    context.source[context.index - 1] in ";|&(){}"
            },
        ),
        BashHeredocRule,
        DelimitedRule(
            startDelimiter = "\$'",
            endDelimiter = "'",
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
        BashStringRule,
        DelimitedRule(
            startDelimiter = "`",
            endDelimiter = "`",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
        ),
        ShellExpansionRule,
        RegexRule(
            pattern = Regex("""(?:\d+#[0-9A-Za-z]+|0[xX][0-9A-Fa-f]+|\d+(?:\.\d+)?)"""),
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        BashIdentifierRule,
        RegexRule(
            pattern = Regex("""(?:&&|\|\||;;|;&|;;&|<<-?|>>|<<<|>&|<&|==|!=|=~|[|&;<>!=+\-*/%])"""),
            scope = TokenScope.OPERATOR,
            nextKind = LexemeKind.Operator,
        ),
        RegexRule(
            pattern = Regex("""[()\[\]{}]"""),
            scope = TokenScope.PUNCTUATION,
        ),
    )
}
