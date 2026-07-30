package me.rerere.highlight.kotlin.languages.cfamily

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.cfamily.rules.CFamilyIdentifierRule
import me.rerere.highlight.kotlin.languages.cfamily.rules.CFamilyLineCommentRule
import me.rerere.highlight.kotlin.languages.cfamily.rules.CFamilyStringRule
import me.rerere.highlight.kotlin.languages.cfamily.rules.CPreprocessorRule

internal fun createCFamilyRules(dialect: CFamilyDialect): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        CPreprocessorRule,
        CFamilyLineCommentRule,
        DelimitedRule(
            startDelimiter = "/*",
            endDelimiter = "*/",
            scope = TokenScope.COMMENT,
        ),
        CFamilyStringRule,
        RegexRule(
            pattern = CFamilyGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        CFamilyIdentifierRule(dialect),
        RegexRule(
            pattern = Regex(
                """(?:<=>|>>=|<<=|->\*|\.\*|->|::|\+\+|--|==|!=|<=|>=|&&|\|\||""" +
                    """\+=|-=|\*=|/=|%=|&=|\|=|\^=|>>|<<|[=+\-*/%<>!~&|^?:])""",
            ),
            scope = { _, _ -> TokenScope.OPERATOR },
            nextKind = { _, match ->
                if (match.value in propertyOperators) {
                    LexemeKind.PropertyAccess
                } else {
                    LexemeKind.Operator
                }
            },
        ),
        RegexRule(
            pattern = Regex("""(?:\.\.\.|[{}()\[\];,.])"""),
            scope = { _, _ -> TokenScope.PUNCTUATION },
            nextKind = { _, match ->
                if (match.value == ".") LexemeKind.PropertyAccess else null
            },
        ),
    )
}

private val propertyOperators = setOf(".", "->", "->*", ".*", "::")
