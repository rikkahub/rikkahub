package me.rerere.highlight.kotlin.languages.cmake.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.cmake.CMakeGrammar

internal object CMakeIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!CMakeGrammar.isIdentifierStart(context.source[context.index])) return null

        var cursor = context.index + 1
        while (
            cursor < context.endIndex &&
            CMakeGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }

        val word = context.source.substring(context.index, cursor)
        val normalized = word.lowercase()
        val next = context.nextNonWhitespace(cursor)
        val scope = when {
            normalized in CMakeGrammar.booleans -> TokenScope.BOOLEAN
            normalized in CMakeGrammar.keywords -> TokenScope.KEYWORD
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            upperCaseConstant.matches(word) -> TokenScope.CONSTANT
            else -> null
        }
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = scope,
            nextKind = if (scope == TokenScope.KEYWORD) {
                LexemeKind.Keyword
            } else {
                LexemeKind.Value
            },
        )
    }

    private fun MatchContext.nextNonWhitespace(startIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && source[cursor].isWhitespace()) cursor++
        return cursor
    }

    private val upperCaseConstant = Regex("""[A-Z][A-Z0-9_]+""")
}
