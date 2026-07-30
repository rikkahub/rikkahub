package me.rerere.highlight.kotlin.languages.python

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.python.rules.PythonDecoratorRule
import me.rerere.highlight.kotlin.languages.python.rules.PythonIdentifierRule
import me.rerere.highlight.kotlin.languages.python.rules.PythonStringRule

internal fun createPythonRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        DelimitedRule(
            startDelimiter = "#",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
        ),
        PythonDecoratorRule,
        PythonStringRule,
        RegexRule(
            pattern = PythonGrammar.numberPattern,
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        PythonIdentifierRule,
        RegexRule(
            pattern = Regex(
                """(?:\*\*=|//=|>>=|<<=|:=|==|!=|<=|>=|->|\*\*|//|>>|<<|""" +
                    """\+=|-=|\*=|/=|%=|@=|&=|\|=|\^=|[+\-*/%@&|^~<>=:])""",
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
