package me.rerere.highlight.kotlin.languages.kotlin.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.kotlin.KotlinGrammar

internal object KotlinStringRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '"') return null

        val isRaw = context.source.startsWith("\"\"\"", context.index)
        val delimiterLength = if (isRaw) 3 else 1
        var cursor = context.index + delimiterLength
        var stringStart = context.index
        val emitter = TokenEmitter()

        while (cursor < context.endIndex) {
            when {
                isRaw && context.source.startsWith("\"\"\"", cursor) -> {
                    cursor += 3
                    emitter.string(context, stringStart, cursor)
                    return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
                }
                !isRaw && context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                !isRaw && context.source[cursor] == '"' -> {
                    cursor++
                    emitter.string(context, stringStart, cursor)
                    return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
                }
                !isRaw &&
                    (context.source[cursor] == '\n' || context.source[cursor] == '\r') -> break
                context.source.startsWith("\${", cursor) -> {
                    emitter.string(context, stringStart, cursor)
                    emitter.token("\${", TokenScope.PUNCTUATION)
                    cursor += 2

                    val expression = context.highlightBalanced(cursor)
                    emitter.appendAll(expression.tokens)
                    cursor = expression.endIndex
                    if (
                        cursor < context.endIndex &&
                        context.source[cursor] == '}'
                    ) {
                        emitter.token("}", TokenScope.PUNCTUATION)
                        cursor++
                    }
                    stringStart = cursor
                }
                context.source[cursor] == '$' &&
                    KotlinGrammar.isIdentifierStart(
                        context.source.getOrNull(cursor + 1) ?: '\u0000',
                    ) -> {
                    emitter.string(context, stringStart, cursor)
                    val variableStart = cursor
                    cursor += 2
                    while (
                        cursor < context.endIndex &&
                        KotlinGrammar.isIdentifierPart(context.source[cursor])
                    ) {
                        cursor++
                    }
                    emitter.token(
                        context.source.substring(variableStart, cursor),
                        TokenScope.VARIABLE,
                    )
                    stringStart = cursor
                }
                else -> cursor++
            }
        }

        emitter.string(context, stringStart, cursor)
        return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
    }

    private fun TokenEmitter.string(
        context: MatchContext,
        startIndex: Int,
        endIndex: Int,
    ) {
        token(
            context.source.substring(startIndex, endIndex),
            TokenScope.STRING,
        )
    }
}
