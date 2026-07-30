package me.rerere.highlight.kotlin.languages.cfamily.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object CFamilyLineCommentRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!context.source.startsWith("//", context.index)) return null

        var cursor = context.index + 2
        while (cursor < context.endIndex) {
            if (context.source[cursor] != '\n' && context.source[cursor] != '\r') {
                cursor++
                continue
            }

            var previous = cursor - 1
            if (
                context.source[cursor] == '\n' &&
                previous >= context.index &&
                context.source[previous] == '\r'
            ) {
                previous--
            }
            if (previous < context.index || context.source[previous] != '\\') break

            cursor++
            if (
                context.source[cursor - 1] == '\r' &&
                cursor < context.endIndex &&
                context.source[cursor] == '\n'
            ) {
                cursor++
            }
        }

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.COMMENT,
            nextKind = LexemeKind.Value,
        )
    }
}
