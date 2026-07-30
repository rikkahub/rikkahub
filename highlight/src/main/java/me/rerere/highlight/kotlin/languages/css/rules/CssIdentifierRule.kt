package me.rerere.highlight.kotlin.languages.css.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.css.CssGrammar
import me.rerere.highlight.kotlin.languages.css.isInDeclarationValue
import me.rerere.highlight.kotlin.languages.css.isInSelector

internal object CssIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val match = CssGrammar.identifierPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val endIndex = match.range.last + 1
        if (endIndex > context.endIndex) return null

        val word = match.value.lowercase()
        val next = context.nextNonWhitespace(endIndex)
        val scope = when {
            word in CssGrammar.globalValues -> TokenScope.KEYWORD
            word in CssGrammar.atRuleModifiers -> TokenScope.KEYWORD
            word == "from" || word == "to" -> TokenScope.KEYWORD
            next < context.endIndex && context.source[next] == '(' -> TokenScope.FUNCTION
            next < context.endIndex &&
                context.source[next] == ':' &&
                !context.isInDeclarationValue() -> TokenScope.PROPERTY
            context.isInSelector() -> TokenScope.TAG
            else -> null
        }
        return context.tokenMatch(
            matchEndIndex = endIndex,
            scope = scope,
            nextKind = if (scope == TokenScope.PROPERTY) {
                LexemeKind.PropertyAccess
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
}
