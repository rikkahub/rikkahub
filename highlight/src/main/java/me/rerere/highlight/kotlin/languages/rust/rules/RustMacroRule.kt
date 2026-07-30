package me.rerere.highlight.kotlin.languages.rust.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.rust.RustGrammar

internal object RustMacroRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        var cursor = context.index
        if (context.source.startsWith("r#", cursor)) cursor += 2
        if (
            cursor >= context.endIndex ||
            !RustGrammar.isIdentifierStart(context.source[cursor])
        ) {
            return null
        }

        cursor++
        while (
            cursor < context.endIndex &&
            RustGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }
        while (cursor < context.endIndex && context.source[cursor].isWhitespace()) cursor++
        if (context.source.getOrNull(cursor) != '!') return null
        cursor++

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.FUNCTION,
            nextKind = LexemeKind.Value,
        )
    }
}
