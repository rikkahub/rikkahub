package me.rerere.highlight.kotlin.languages.python.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.python.PythonGrammar

internal object PythonIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!PythonGrammar.isIdentifierStart(context.source[context.index])) return null

        var cursor = context.index + 1
        while (
            cursor < context.endIndex &&
            PythonGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }

        val word = context.source.substring(context.index, cursor)
        val next = context.nextNonWhitespace(cursor)
        val scope = when {
            word == "True" || word == "False" -> TokenScope.BOOLEAN
            word in PythonGrammar.constants -> TokenScope.CONSTANT
            word in PythonGrammar.keywords -> TokenScope.KEYWORD
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.CLASS_NAME
            context.previousKind == LexemeKind.FunctionDeclaration -> TokenScope.FUNCTION
            word == "self" || word == "cls" -> TokenScope.VARIABLE
            word in PythonGrammar.builtInTypes -> TokenScope.CLASS_NAME
            word in PythonGrammar.builtIns -> TokenScope.FUNCTION
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            context.previousKind == LexemeKind.PropertyAccess -> TokenScope.PROPERTY
            word.firstOrNull()?.isUpperCase() == true -> TokenScope.CLASS_NAME
            else -> null
        }
        val nextKind = when (word) {
            "class" -> LexemeKind.ClassDeclaration
            "def" -> LexemeKind.FunctionDeclaration
            in PythonGrammar.keywords -> LexemeKind.Keyword
            else -> LexemeKind.Value
        }
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = scope,
            nextKind = nextKind,
        )
    }

    private fun MatchContext.nextNonWhitespace(startIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && source[cursor].isWhitespace()) cursor++
        return cursor
    }
}
