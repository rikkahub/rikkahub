package me.rerere.highlight.kotlin.engine.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch

internal class DelimitedRule(
    private val startDelimiter: String,
    private val endDelimiter: String?,
    private val scope: String,
    private val nextKind: LexemeKind? = null,
    private val escapeCharacter: Char? = null,
    private val stopAtLineBreak: Boolean = false,
    private val condition: (MatchContext) -> Boolean = { true },
) : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!condition(context) || !context.source.startsWith(startDelimiter, context.index)) {
            return null
        }

        var cursor = context.index + startDelimiter.length
        while (cursor < context.endIndex) {
            when {
                escapeCharacter != null && context.source[cursor] == escapeCharacter -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }

                endDelimiter != null && context.source.startsWith(endDelimiter, cursor) -> {
                    cursor += endDelimiter.length
                    break
                }

                stopAtLineBreak &&
                    (context.source[cursor] == '\n' || context.source[cursor] == '\r') -> break
                else -> cursor++
            }
        }

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = scope,
            nextKind = nextKind,
        )
    }
}
