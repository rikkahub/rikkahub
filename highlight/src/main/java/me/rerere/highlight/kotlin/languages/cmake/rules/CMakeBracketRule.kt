package me.rerere.highlight.kotlin.languages.cmake.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object CMakeBracketRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val isComment = context.source.startsWith("#[", context.index)
        val bracketIndex = context.index + if (isComment) 1 else 0
        if (context.source.getOrNull(bracketIndex) != '[') return null

        var cursor = bracketIndex + 1
        while (cursor < context.endIndex && context.source[cursor] == '=') cursor++
        if (cursor >= context.endIndex || context.source[cursor] != '[') return null

        val equals = context.source.substring(bracketIndex + 1, cursor)
        val closingDelimiter = "]$equals]"
        val closingIndex = context.source.indexOf(closingDelimiter, cursor + 1)
            .takeIf { it in (cursor + 1) until context.endIndex }
        val endIndex = closingIndex
            ?.plus(closingDelimiter.length)
            ?.coerceAtMost(context.endIndex)
            ?: context.endIndex
        return context.tokenMatch(
            matchEndIndex = endIndex,
            scope = if (isComment) TokenScope.COMMENT else TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }
}
