package me.rerere.highlight.kotlin.languages.javascript.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object RegularExpressionRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (
            context.source[context.index] != '/' ||
            !context.previousKind.canStartExpression
        ) {
            return null
        }

        var cursor = context.index + 1
        var inCharacterClass = false

        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                '\n', '\r' -> return null
                '[' -> {
                    inCharacterClass = true
                    cursor++
                }

                ']' -> {
                    inCharacterClass = false
                    cursor++
                }

                '/' -> {
                    if (inCharacterClass) {
                        cursor++
                    } else {
                        cursor++
                        while (
                            cursor < context.endIndex &&
                            context.source[cursor].isLetter()
                        ) {
                            cursor++
                        }
                        return context.tokenMatch(
                            matchEndIndex = cursor,
                            scope = TokenScope.REGEX,
                            nextKind = LexemeKind.Value,
                        )
                    }
                }

                else -> cursor++
            }
        }
        return null
    }
}
