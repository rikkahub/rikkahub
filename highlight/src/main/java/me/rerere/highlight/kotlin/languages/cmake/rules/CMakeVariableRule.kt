package me.rerere.highlight.kotlin.languages.cmake.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object CMakeVariableRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        return CMakeVariableParser.parse(context, context.index)
    }
}

internal object CMakeVariableParser {
    fun parse(context: MatchContext, startIndex: Int): RuleMatch? {
        if (context.source.getOrNull(startIndex) != '$') return null

        val opening = when {
            context.source.startsWith("\${", startIndex) -> Opening(startIndex + 2, '{', '}')
            context.source.startsWith("\$ENV{", startIndex) -> Opening(startIndex + 5, '{', '}')
            context.source.startsWith("\$CACHE{", startIndex) -> Opening(startIndex + 7, '{', '}')
            context.source.startsWith("\$<", startIndex) -> Opening(startIndex + 2, '<', '>')
            else -> return null
        }

        var cursor = opening.contentStart
        var depth = 1
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                opening.openingCharacter -> depth++
                opening.closingCharacter -> {
                    depth--
                    if (depth == 0) {
                        cursor++
                        return variableMatch(context, startIndex, cursor)
                    }
                }
            }
            cursor++
        }
        return variableMatch(context, startIndex, cursor)
    }

    private fun variableMatch(
        context: MatchContext,
        startIndex: Int,
        endIndex: Int,
    ): RuleMatch {
        val content = context.source.substring(startIndex, endIndex)
        return RuleMatch(
            endIndex = endIndex,
            tokens = listOf(
                me.rerere.highlight.HighlightToken.Styled(
                    content = content,
                    type = TokenScope.VARIABLE,
                ),
            ),
            nextKind = LexemeKind.Value,
        )
    }

    private data class Opening(
        val contentStart: Int,
        val openingCharacter: Char,
        val closingCharacter: Char,
    )
}
