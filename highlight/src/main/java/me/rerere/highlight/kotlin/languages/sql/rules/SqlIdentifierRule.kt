package me.rerere.highlight.kotlin.languages.sql.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.sql.SqlGrammar

internal object SqlIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val match = SqlGrammar.identifierPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val endIndex = match.range.last + 1
        if (endIndex > context.endIndex) return null

        val word = match.value.lowercase()
        val next = context.nextNonWhitespace(endIndex)
        val scope = when {
            context.previousKind == LexemeKind.PropertyAccess -> TokenScope.PROPERTY
            word == "true" || word == "false" -> TokenScope.BOOLEAN
            word == "null" || word == "unknown" -> TokenScope.CONSTANT
            word in SqlGrammar.types -> TokenScope.CLASS_NAME
            word in SqlGrammar.functions &&
                next < context.endIndex &&
                context.source[next] == '(' -> TokenScope.FUNCTION
            word !in SqlGrammar.keywords &&
                next < context.endIndex &&
                context.source[next] == '(' -> TokenScope.FUNCTION
            word in SqlGrammar.keywords -> TokenScope.KEYWORD
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.CLASS_NAME
            else -> null
        }
        val nextKind = when {
            word in SqlGrammar.objectStarterKeywords -> LexemeKind.ClassDeclaration
            word in SqlGrammar.keywords -> LexemeKind.Keyword
            else -> LexemeKind.Value
        }
        return context.tokenMatch(
            matchEndIndex = endIndex,
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
