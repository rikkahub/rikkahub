package me.rerere.highlight.kotlin.languages.rust.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.rust.RustGrammar

internal object RustLifetimeRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (
            context.source[context.index] != '\'' ||
            !RustGrammar.isIdentifierStart(
                context.source.getOrNull(context.index + 1) ?: '\u0000',
            )
        ) {
            return null
        }

        var cursor = context.index + 2
        while (
            cursor < context.endIndex &&
            RustGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }
        if (context.source.getOrNull(cursor) == '\'') return null

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
        )
    }
}
