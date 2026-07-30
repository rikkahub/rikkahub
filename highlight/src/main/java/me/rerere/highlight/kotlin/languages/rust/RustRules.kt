package me.rerere.highlight.kotlin.languages.rust

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.rust.rules.RustAttributeRule
import me.rerere.highlight.kotlin.languages.rust.rules.RustIdentifierRule
import me.rerere.highlight.kotlin.languages.rust.rules.RustLifetimeRule
import me.rerere.highlight.kotlin.languages.rust.rules.RustMacroRule
import me.rerere.highlight.kotlin.languages.rust.rules.RustNestedCommentRule
import me.rerere.highlight.kotlin.languages.rust.rules.RustStringRule

internal fun createRustRules(): List<GrammarRule> {
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
        RustNestedCommentRule,
        RustAttributeRule,
        RustStringRule,
        RustLifetimeRule,
        RegexRule(
            pattern = RustGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RustMacroRule,
        RustIdentifierRule,
        RegexRule(
            pattern = Regex(
                """(?:<<=|>>=|\.\.=|\.\.|=>|->|::|==|!=|<=|>=|&&|\|\||""" +
                    """\+=|-=|\*=|/=|%=|&=|\|=|\^=|<<|>>|[=+\-*/%<>!&|^~?:@])""",
            ),
            scope = { _, _ -> TokenScope.OPERATOR },
            nextKind = { _, match ->
                if (match.value == "::") LexemeKind.PropertyAccess else LexemeKind.Operator
            },
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
