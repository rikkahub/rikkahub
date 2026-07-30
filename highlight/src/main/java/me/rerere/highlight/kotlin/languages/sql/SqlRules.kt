package me.rerere.highlight.kotlin.languages.sql

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.sql.rules.SqlDollarQuotedStringRule
import me.rerere.highlight.kotlin.languages.sql.rules.SqlIdentifierRule
import me.rerere.highlight.kotlin.languages.sql.rules.SqlQuotedIdentifierRule
import me.rerere.highlight.kotlin.languages.sql.rules.SqlStringRule

internal fun createSqlRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        DelimitedRule(
            startDelimiter = "--",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
        ),
        DelimitedRule(
            startDelimiter = "#",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
            condition = { context ->
                context.index == 0 ||
                    context.source[context.index - 1].isWhitespace()
            },
        ),
        DelimitedRule(
            startDelimiter = "/*",
            endDelimiter = "*/",
            scope = TokenScope.COMMENT,
        ),
        SqlDollarQuotedStringRule,
        SqlStringRule,
        SqlQuotedIdentifierRule,
        RegexRule(
            pattern = Regex("""(?::[A-Za-z_][A-Za-z0-9_]*|@[A-Za-z_][A-Za-z0-9_]*|\$\d+|\?)"""),
            scope = TokenScope.VARIABLE,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = SqlGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        SqlIdentifierRule,
        RegexRule(
            pattern = Regex("""(?:<>|!=|<=|>=|:=|::|->>|->|\|\||&&|[-+*/%=<>~!&|^])"""),
            scope = TokenScope.OPERATOR,
            nextKind = LexemeKind.Operator,
        ),
        RegexRule(
            pattern = Regex("""[()\[\]{},.;]"""),
            scope = { _, _ -> TokenScope.PUNCTUATION },
            nextKind = { _, match ->
                if (match.value == ".") LexemeKind.PropertyAccess else null
            },
        ),
    )
}
