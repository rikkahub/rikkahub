package me.rerere.highlight.kotlin.languages.javascript.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object TemplateStringRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '`') return null

        val emitter = TokenEmitter()
        var cursor = context.index + 1
        var stringStart = context.index

        while (cursor < context.endIndex) {
            when {
                context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }

                context.source[cursor] == '`' -> {
                    cursor++
                    emitter.token(
                        content = context.source.substring(stringStart, cursor),
                        type = TokenScope.STRING,
                    )
                    return RuleMatch(
                        endIndex = cursor,
                        tokens = emitter.build(),
                        nextKind = LexemeKind.Value,
                    )
                }

                context.source.startsWith("\${", cursor) -> {
                    emitter.token(
                        content = context.source.substring(stringStart, cursor),
                        type = TokenScope.STRING,
                    )
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

                else -> cursor++
            }
        }

        emitter.token(
            content = context.source.substring(stringStart, cursor),
            type = TokenScope.STRING,
        )
        return RuleMatch(
            endIndex = cursor,
            tokens = emitter.build(),
            nextKind = LexemeKind.Value,
        )
    }
}
