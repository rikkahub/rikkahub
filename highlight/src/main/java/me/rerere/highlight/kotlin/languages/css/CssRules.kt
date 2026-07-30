package me.rerere.highlight.kotlin.languages.css

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.css.rules.CssAttributeSelectorRule
import me.rerere.highlight.kotlin.languages.css.rules.CssHashRule
import me.rerere.highlight.kotlin.languages.css.rules.CssIdentifierRule
import me.rerere.highlight.kotlin.languages.css.rules.CssUrlRule

internal fun createCssRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        DelimitedRule(
            startDelimiter = "/*",
            endDelimiter = "*/",
            scope = TokenScope.COMMENT,
        ),
        CssUrlRule,
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
            escapeCharacter = '\\',
        ),
        CssAttributeSelectorRule,
        RegexRule(
            pattern = Regex("""!important\b""", RegexOption.IGNORE_CASE),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""@[A-Za-z_-][A-Za-z0-9_-]*"""),
            scope = TokenScope.KEYWORD,
            nextKind = LexemeKind.Keyword,
        ),
        RegexRule(
            pattern = Regex("""--[A-Za-z_][A-Za-z0-9_-]*"""),
            scope = TokenScope.VARIABLE,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""[Uu]\+[0-9A-Fa-f?]+(?:-[0-9A-Fa-f]+)?"""),
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        CssHashRule,
        RegexRule(
            pattern = CssGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""\.[A-Za-z_-][A-Za-z0-9_-]*"""),
            scope = TokenScope.CLASS_NAME,
            nextKind = LexemeKind.Value,
            condition = { !it.isInDeclarationValue() },
        ),
        RegexRule(
            pattern = Regex("""::?[A-Za-z_-][A-Za-z0-9_-]*"""),
            scope = TokenScope.KEYWORD,
            nextKind = LexemeKind.Keyword,
            condition = { !it.isInDeclarationValue() },
        ),
        CssIdentifierRule,
        RegexRule(
            pattern = Regex("""(?:~=|\|=|\^=|\$=|\*=|[>+~*=|])"""),
            scope = TokenScope.OPERATOR,
            nextKind = LexemeKind.Operator,
        ),
        RegexRule(
            pattern = Regex("""[{}()\[\];,:]"""),
            scope = TokenScope.PUNCTUATION,
        ),
    )
}
