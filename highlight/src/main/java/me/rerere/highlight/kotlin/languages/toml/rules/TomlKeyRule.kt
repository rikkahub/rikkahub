package me.rerere.highlight.kotlin.languages.toml.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object TomlKeyRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        var cursor = context.index
        val components = mutableListOf<IntRange>()
        val dots = mutableListOf<Int>()

        while (true) {
            val componentEnd = parseComponent(context, cursor) ?: return null
            components += cursor until componentEnd
            cursor = skipHorizontalWhitespace(context, componentEnd)
            if (context.source.getOrNull(cursor) != '.') break
            dots += cursor
            cursor = skipHorizontalWhitespace(context, cursor + 1)
        }

        if (context.source.getOrNull(cursor) != '=') return null

        val emitter = TokenEmitter()
        var outputCursor = context.index
        components.forEachIndexed { index, range ->
            emitter.plain(context.source.substring(outputCursor, range.first))
            emitter.token(
                context.source.substring(range.first, range.last + 1),
                TokenScope.PROPERTY,
            )
            outputCursor = range.last + 1
            dots.getOrNull(index)?.let { dot ->
                emitter.plain(context.source.substring(outputCursor, dot))
                emitter.token(".", TokenScope.PUNCTUATION)
                outputCursor = dot + 1
            }
        }
        emitter.plain(context.source.substring(outputCursor, cursor))
        return RuleMatch(
            endIndex = cursor,
            tokens = emitter.build(),
            nextKind = LexemeKind.Value,
        )
    }

    private fun parseComponent(context: MatchContext, startIndex: Int): Int? {
        return when (context.source.getOrNull(startIndex)) {
            '"' -> parseQuoted(context, startIndex, '"', escapes = true)
            '\'' -> parseQuoted(context, startIndex, '\'', escapes = false)
            else -> parseBare(context, startIndex)
        }
    }

    private fun parseBare(context: MatchContext, startIndex: Int): Int? {
        var cursor = startIndex
        while (
            cursor < context.endIndex &&
            (context.source[cursor].isLetterOrDigit() ||
                context.source[cursor] == '_' ||
                context.source[cursor] == '-')
        ) {
            cursor++
        }
        return cursor.takeIf { it > startIndex }
    }

    private fun parseQuoted(
        context: MatchContext,
        startIndex: Int,
        quote: Char,
        escapes: Boolean,
    ): Int? {
        var cursor = startIndex + 1
        while (cursor < context.endIndex) {
            when {
                escapes && context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                context.source[cursor] == quote -> return cursor + 1
                context.source[cursor] == '\n' || context.source[cursor] == '\r' -> return null
                else -> cursor++
            }
        }
        return null
    }

    private fun skipHorizontalWhitespace(context: MatchContext, startIndex: Int): Int {
        var cursor = startIndex
        while (
            cursor < context.endIndex &&
            (context.source[cursor] == ' ' || context.source[cursor] == '\t')
        ) {
            cursor++
        }
        return cursor
    }
}
