package me.rerere.highlight.kotlin.languages.cmake.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object CMakeStringRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '"') return null

        val emitter = TokenEmitter()
        var cursor = context.index + 1
        var stringStart = context.index
        while (cursor < context.endIndex) {
            when {
                context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                context.source[cursor] == '"' -> {
                    cursor++
                    emitter.string(context, stringStart, cursor)
                    return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
                }
                context.source[cursor] == '$' -> {
                    val variable = CMakeVariableParser.parse(context, cursor)
                    if (variable == null) {
                        cursor++
                    } else {
                        emitter.string(context, stringStart, cursor)
                        emitter.appendAll(variable.tokens)
                        cursor = variable.endIndex
                        stringStart = cursor
                    }
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
