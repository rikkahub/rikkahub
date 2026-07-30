package me.rerere.highlight.kotlin.languages.css.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object CssUrlRule : GrammarRule {
    private val openingPattern = Regex("""(?:url|data-uri)(\s*)\(""", RegexOption.IGNORE_CASE)

    override fun match(context: MatchContext): RuleMatch? {
        val opening = openingPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val openingEnd = opening.range.last + 1
        if (openingEnd > context.endIndex) return null

        var cursor = openingEnd
        var quote: Char? = null
        while (cursor < context.endIndex) {
            val char = context.source[cursor]
            when {
                char == '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                quote != null -> {
                    if (char == quote) quote = null
                    cursor++
                }
                char == '"' || char == '\'' -> {
                    quote = char
                    cursor++
                }
                char == ')' -> break
                else -> cursor++
            }
        }

        val whitespaceOffset = opening.value.indexOfFirst { it == ' ' || it == '\t' }
        val nameEnd = if (whitespaceOffset == -1) {
            openingEnd - 1
        } else {
            context.index + whitespaceOffset
        }
        val emitter = TokenEmitter()
        emitter.token(
            context.source.substring(context.index, nameEnd),
            TokenScope.FUNCTION,
        )
        emitter.plain(context.source.substring(nameEnd, openingEnd - 1))
        emitter.token("(", TokenScope.PUNCTUATION)
        emitter.token(
            context.source.substring(openingEnd, cursor),
            TokenScope.STRING,
        )
        if (cursor < context.endIndex && context.source[cursor] == ')') {
            emitter.token(")", TokenScope.PUNCTUATION)
            cursor++
        }
        return RuleMatch(
            endIndex = cursor,
            tokens = emitter.build(),
            nextKind = LexemeKind.Value,
        )
    }
}
