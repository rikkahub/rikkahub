package me.rerere.highlight.kotlin.languages.kotlin

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.kotlin.rules.KotlinIdentifierRule
import me.rerere.highlight.kotlin.languages.kotlin.rules.KotlinNestedCommentRule
import me.rerere.highlight.kotlin.languages.kotlin.rules.KotlinStringRule

internal fun createKotlinRules(): List<GrammarRule> {
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
            startDelimiter = "//",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
        ),
        KotlinNestedCommentRule,
        KotlinStringRule,
        DelimitedRule(
            startDelimiter = "'",
            endDelimiter = "'",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
            stopAtLineBreak = true,
        ),
        RegexRule(
            pattern = Regex(
                """(?!(?:break|continue|return|this|super)@)""" +
                    """[A-Za-z_][A-Za-z0-9_]*@""",
            ),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex(
                """@(?:file|property|field|get|set|receiver|param|setparam|delegate)""" +
                    """:[A-Za-z_][A-Za-z0-9_]*""",
            ),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""@[A-Za-z_][A-Za-z0-9_]*"""),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = KotlinGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        KotlinIdentifierRule,
        RegexRule(
            pattern = Regex(
                """(?:===|!==|>>>|>>|<<|\.\.<|\.\.|==|!=|<=|>=|&&|\|\||\+\+|--|""" +
                    """\?:|\?\.|!!|->|::|\+=|-=|\*=|/=|%=|[=+\-*/%<>!&|^?:])""",
            ),
            scope = { _, _ -> TokenScope.OPERATOR },
            nextKind = { _, match ->
                if (match.value == ":") LexemeKind.ClassDeclaration else LexemeKind.Operator
            },
        ),
        RegexRule(
            pattern = Regex("""[{}()\[\];,.]"""),
            scope = { _, _ -> TokenScope.PUNCTUATION },
            nextKind = { context, match ->
                when (match.value) {
                    "." -> LexemeKind.PropertyAccess
                    else -> if (
                        context.previousKind == LexemeKind.VariableDeclaration
                    ) {
                        LexemeKind.VariableDeclaration
                    } else {
                        null
                    }
                }
            },
        ),
    )
}
