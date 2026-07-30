package me.rerere.highlight.kotlin.languages.sql.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object SqlQuotedIdentifierRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val opening = context.source[context.index]
        val closing = when (opening) {
            '"' -> '"'
            '`' -> '`'
            '[' -> ']'
            else -> return null
        }

        var cursor = context.index + 1
        while (cursor < context.endIndex) {
            when {
                context.source[cursor] == closing &&
                    context.source.getOrNull(cursor + 1) == closing -> cursor += 2
                context.source[cursor] == closing -> {
                    cursor++
                    break
                }
                else -> cursor++
            }
        }
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.CLASS_NAME,
            nextKind = LexemeKind.Value,
        )
    }
}
