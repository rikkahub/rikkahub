package me.rerere.highlight.kotlin.languages.rust.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object RustAttributeRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '#') return null

        var cursor = context.index + 1
        if (context.source.getOrNull(cursor) == '!') cursor++
        if (context.source.getOrNull(cursor) != '[') return null

        var depth = 0
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    cursor++
                    if (depth == 0) {
                        return context.tokenMatch(
                            matchEndIndex = cursor,
                            scope = TokenScope.IMPORTANT,
                            nextKind = LexemeKind.Value,
                        )
                    }
                    continue
                }
                '"' -> cursor = skipQuotedString(context, cursor)
            }
            cursor++
        }

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        )
    }

    private fun skipQuotedString(context: MatchContext, startIndex: Int): Int {
        var cursor = startIndex + 1
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                '"' -> return cursor
                else -> cursor++
            }
        }
        return cursor
    }
}
