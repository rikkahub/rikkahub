package me.rerere.highlight.kotlin.languages.bash.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object BashStringRule : GrammarRule {
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
                    emitter.token(
                        context.source.substring(stringStart, cursor),
                        TokenScope.STRING,
                    )
                    return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
                }
                context.source[cursor] == '$' -> {
                    val expansion = ShellExpansionParser.parse(context, cursor)
                    if (expansion == null) {
                        cursor++
                    } else {
                        emitter.token(
                            context.source.substring(stringStart, cursor),
                            TokenScope.STRING,
                        )
                        emitter.appendAll(expansion.tokens)
                        cursor = expansion.endIndex
                        stringStart = cursor
                    }
                }
                else -> cursor++
            }
        }

        emitter.token(
            context.source.substring(stringStart, cursor),
            TokenScope.STRING,
        )
        return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
    }
}
