package me.rerere.highlight.kotlin.languages.rust.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object RustNestedCommentRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!context.source.startsWith("/*", context.index)) return null

        var cursor = context.index + 2
        var depth = 1
        while (cursor < context.endIndex) {
            when {
                context.source.startsWith("/*", cursor) -> {
                    depth++
                    cursor += 2
                }
                context.source.startsWith("*/", cursor) -> {
                    depth--
                    cursor += 2
                    if (depth == 0) break
                }
                else -> cursor++
            }
        }
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.COMMENT,
            nextKind = LexemeKind.Value,
        )
    }
}
