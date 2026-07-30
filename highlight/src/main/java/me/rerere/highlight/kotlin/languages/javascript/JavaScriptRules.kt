package me.rerere.highlight.kotlin.languages.javascript

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.NUMBER_PATTERN
import me.rerere.highlight.kotlin.languages.javascript.JavaScriptGrammar.OPERATORS
import me.rerere.highlight.kotlin.languages.javascript.rules.IdentifierRule
import me.rerere.highlight.kotlin.languages.javascript.rules.JsxRule
import me.rerere.highlight.kotlin.languages.javascript.rules.RegularExpressionRule
import me.rerere.highlight.kotlin.languages.javascript.rules.TemplateStringRule

internal fun createJavaScriptRules(
    dialect: JavaScriptDialect,
): List<GrammarRule> {
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
        DelimitedRule(
            startDelimiter = "/*",
            endDelimiter = "*/",
            scope = TokenScope.COMMENT,
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
            startDelimiter = "\"",
            endDelimiter = "\"",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
            stopAtLineBreak = true,
        ),
        TemplateStringRule,
        RegexRule(
            pattern = Regex("""@[A-Za-z${'$'}_][0-9A-Za-z${'$'}_]*"""),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
            condition = { dialect == JavaScriptDialect.TypeScript },
        ),
        JsxRule,
        RegularExpressionRule,
        RegexRule(
            pattern = NUMBER_PATTERN,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        IdentifierRule(dialect),
        RegexRule(
            pattern = Regex("""[{}]"""),
            scope = { _, _ -> TokenScope.PUNCTUATION },
            nextKind = { _, match ->
                if (match.value == "{") LexemeKind.OpeningDelimiter else LexemeKind.Value
            },
        ),
        RegexRule(
            pattern = Regex("""[()\[\];,.]"""),
            scope = { _, _ -> TokenScope.PUNCTUATION },
            nextKind = { _, match ->
                when (match.value) {
                    "(", "[", ",", ";" -> LexemeKind.OpeningDelimiter
                    ")", "]" -> LexemeKind.Value
                    "." -> LexemeKind.PropertyAccess
                    else -> LexemeKind.Operator
                }
            },
        ),
        RegexRule(
            pattern = Regex(OPERATORS.joinToString(separator = "|", transform = Regex::escape)),
            scope = { _, _ -> TokenScope.OPERATOR },
            nextKind = { _, match ->
                when (match.value) {
                    "++", "--" -> LexemeKind.Value
                    "?." -> LexemeKind.PropertyAccess
                    else -> LexemeKind.Operator
                }
            },
        ),
    )
}
