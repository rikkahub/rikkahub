package me.rerere.highlight.kotlin.languages.toml.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object TomlTableRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '[' || !context.isAtLineStart()) return null

        val openingLength = if (context.source.startsWith("[[", context.index)) 2 else 1
        val closing = if (openingLength == 2) "]]" else "]"
        val contentStart = context.index + openingLength
        var cursor = contentStart
        var quote: Char? = null
        while (cursor < context.endIndex) {
            val char = context.source[cursor]
            when {
                char == '\\' && quote == '"' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                quote != null -> {
                    if (char == quote) quote = null
                    cursor++
                }
                char == '"' || char == '\'' -> {
                    quote = char
                    cursor++
                }
                context.source.startsWith(closing, cursor) -> {
                    val emitter = TokenEmitter()
                    emitter.token(
                        context.source.substring(context.index, contentStart),
                        TokenScope.PUNCTUATION,
                    )
                    emitter.token(
                        context.source.substring(contentStart, cursor),
                        TokenScope.CLASS_NAME,
                    )
                    emitter.token(closing, TokenScope.PUNCTUATION)
                    return RuleMatch(
                        endIndex = cursor + closing.length,
                        tokens = emitter.build(),
                        nextKind = LexemeKind.Value,
                    )
                }
                char == '\n' || char == '\r' -> return null
                else -> cursor++
            }
        }
        return null
    }

    private fun MatchContext.isAtLineStart(): Boolean {
        var cursor = index - 1
        while (cursor >= 0 && source[cursor] != '\n' && source[cursor] != '\r') {
            if (source[cursor] != ' ' && source[cursor] != '\t') return false
            cursor--
        }
        return true
    }
}
