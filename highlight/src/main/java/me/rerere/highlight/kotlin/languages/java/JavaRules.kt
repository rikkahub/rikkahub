package me.rerere.highlight.kotlin.languages.java

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.java.rules.JavaIdentifierRule
import me.rerere.highlight.kotlin.languages.jvm.JvmGrammar

internal fun createJavaRules(): List<GrammarRule> {
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
            startDelimiter = "\"\"\"",
            endDelimiter = "\"\"\"",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
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
        RegexRule(
            pattern = Regex("""@interface\b"""),
            scope = TokenScope.KEYWORD,
            nextKind = LexemeKind.ClassDeclaration,
        ),
        RegexRule(
            pattern = Regex("""@[A-Za-z_$][A-Za-z0-9_$]*"""),
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""non-sealed\b"""),
            scope = TokenScope.KEYWORD,
            nextKind = LexemeKind.Keyword,
        ),
        RegexRule(
            pattern = JvmGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        JavaIdentifierRule,
        RegexRule(
            pattern = Regex(
                """(?:>>>=|>>=|<<=|>>>|>>|<<|==|!=|<=|>=|&&|\|\||\+\+|--|""" +
                    """->|::|\+=|-=|\*=|/=|%=|&=|\|=|\^=|[=+\-*/%<>!~&|^?:])""",
            ),
            scope = TokenScope.OPERATOR,
            nextKind = LexemeKind.Operator,
        ),
        RegexRule(
            pattern = Regex("""[{}()\[\];,.]"""),
            scope = { _, _ -> TokenScope.PUNCTUATION },
            nextKind = { _, match ->
                if (match.value == ".") LexemeKind.PropertyAccess else null
            },
        ),
    )
}
