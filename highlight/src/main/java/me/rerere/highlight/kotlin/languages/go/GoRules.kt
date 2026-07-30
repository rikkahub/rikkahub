package me.rerere.highlight.kotlin.languages.go

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.go.rules.GoFunctionRule
import me.rerere.highlight.kotlin.languages.go.rules.GoIdentifierRule

internal fun createGoRules(): List<GrammarRule> {
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
        DelimitedRule(
            startDelimiter = "`",
            endDelimiter = "`",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = GoGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        GoFunctionRule,
        GoIdentifierRule,
        RegexRule(
            pattern = Regex(
                """(?:<<=|>>=|&\^=|==|!=|<=|>=|:=|\+\+|--|&&|\|\||<-|""" +
                    """\+=|-=|\*=|/=|%=|&=|\|=|\^=|<<|>>|&\^|[+\-*/%&|^<>=!:])""",
            ),
            scope = TokenScope.OPERATOR,
            nextKind = LexemeKind.Operator,
        ),
        RegexRule(
            pattern = Regex("""[{}()\[\];,.]"""),
            scope = { _, _ -> TokenScope.PUNCTUATION },
            nextKind = { context, match ->
                when {
                    match.value == "." -> LexemeKind.PropertyAccess
                    context.previousKind == LexemeKind.VariableDeclaration ->
                        LexemeKind.VariableDeclaration
                    else -> null
                }
            },
        ),
    )
}
