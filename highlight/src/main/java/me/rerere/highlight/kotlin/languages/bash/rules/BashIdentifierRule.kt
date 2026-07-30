package me.rerere.highlight.kotlin.languages.bash.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.bash.BashGrammar

internal object BashIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val match = BashGrammar.identifierPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val endIndex = match.range.last + 1
        if (endIndex > context.endIndex) return null

        val word = match.value
        val next = context.nextNonWhitespace(endIndex)
        val scope = when {
            context.previousKind == LexemeKind.ClassDeclaration -> TokenScope.FUNCTION
            word in BashGrammar.keywords -> TokenScope.KEYWORD
            word == "true" || word == "false" -> TokenScope.BOOLEAN
            word in BashGrammar.builtIns -> TokenScope.FUNCTION
            next < context.endIndex &&
                context.source.startsWith("()", next) -> TokenScope.FUNCTION
            BashGrammar.variableNamePattern.matches(word) &&
                next < context.endIndex &&
                context.source[next] == '=' -> TokenScope.VARIABLE
            else -> null
        }
        val nextKind = when {
            word == "function" -> LexemeKind.ClassDeclaration
            word in BashGrammar.keywords -> LexemeKind.Keyword
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
