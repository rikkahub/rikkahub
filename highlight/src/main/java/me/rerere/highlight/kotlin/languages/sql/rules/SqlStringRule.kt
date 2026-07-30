package me.rerere.highlight.kotlin.languages.sql.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object SqlStringRule : GrammarRule {
    private val prefixes = setOf('b', 'B', 'e', 'E', 'n', 'N', 'x', 'X')

    override fun match(context: MatchContext): RuleMatch? {
        val quoteIndex = when {
            context.source[context.index] == '\'' -> context.index
            context.source[context.index] in prefixes &&
                context.source.getOrNull(context.index + 1) == '\'' &&
                !context.hasIdentifierBefore() -> context.index + 1
            else -> return null
        }

        var cursor = quoteIndex + 1
        while (cursor < context.endIndex) {
            when {
                context.source.startsWith("''", cursor) -> cursor += 2
                context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                context.source[cursor] == '\'' -> {
                    cursor++
                    break
                }
                else -> cursor++
            }
        }
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }

    private fun MatchContext.hasIdentifierBefore(): Boolean {
        val previous = source.getOrNull(index - 1) ?: return false
        return previous == '_' || previous == '$' || previous.isLetterOrDigit()
    }
}
