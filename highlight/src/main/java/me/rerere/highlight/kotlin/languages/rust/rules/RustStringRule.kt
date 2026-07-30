package me.rerere.highlight.kotlin.languages.rust.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object RustStringRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val rawStart = parseRawStart(context)
        if (rawStart != null) return matchRawString(context, rawStart)

        val quoteIndex = when {
            context.source[context.index] == '"' || context.source[context.index] == '\'' ->
                context.index
            context.source[context.index] in prefixedStringCharacters &&
                context.source.getOrNull(context.index + 1) in setOf('"', '\'') ->
                context.index + 1
            else -> return null
        }
        return if (context.source[quoteIndex] == '\'') {
            matchCharacter(context, quoteIndex)
        } else {
            matchQuotedString(context, quoteIndex)
        }
    }

    private fun matchQuotedString(
        context: MatchContext,
        quoteIndex: Int,
    ): RuleMatch {
        var cursor = quoteIndex + 1
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                '"' -> {
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

    private fun matchCharacter(
        context: MatchContext,
        quoteIndex: Int,
    ): RuleMatch? {
        var cursor = quoteIndex + 1
        if (cursor >= context.endIndex) return null
        cursor = if (context.source[cursor] == '\\') {
            consumeEscape(context, cursor)
        } else {
            cursor + 1
        }
        if (context.source.getOrNull(cursor) != '\'') return null
        cursor++
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }

    private fun consumeEscape(context: MatchContext, startIndex: Int): Int {
        var cursor = startIndex + 1
        if (cursor >= context.endIndex) return cursor
        cursor = when (context.source[cursor]) {
            'x' -> (cursor + 3).coerceAtMost(context.endIndex)
            'u' -> {
                cursor++
                if (context.source.getOrNull(cursor) == '{') {
                    val closing = context.source.indexOf('}', cursor + 1)
                    if (closing in (cursor + 1) until context.endIndex) closing + 1 else cursor
                } else {
                    cursor
                }
            }
            else -> cursor + 1
        }
        return cursor
    }

    private fun matchRawString(
        context: MatchContext,
        start: RawStringStart,
    ): RuleMatch {
        val closingDelimiter = "\"${"#".repeat(start.hashCount)}"
        val closingIndex = context.source.indexOf(closingDelimiter, start.contentStart)
            .takeIf {
                it in start.contentStart until context.endIndex &&
                    it + closingDelimiter.length <= context.endIndex
            }
        val endIndex = closingIndex?.plus(closingDelimiter.length) ?: context.endIndex
        return context.tokenMatch(
            matchEndIndex = endIndex,
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }

    private fun parseRawStart(context: MatchContext): RawStringStart? {
        var cursor = context.index
        when {
            context.source.startsWith("br", cursor) ||
                context.source.startsWith("cr", cursor) -> cursor += 2
            context.source.getOrNull(cursor) == 'r' -> cursor++
            else -> return null
        }

        var hashCount = 0
        while (context.source.getOrNull(cursor) == '#') {
            hashCount++
            cursor++
        }
        if (context.source.getOrNull(cursor) != '"') return null
        return RawStringStart(
            contentStart = cursor + 1,
            hashCount = hashCount,
        )
    }

    private data class RawStringStart(
        val contentStart: Int,
        val hashCount: Int,
    )

    private val prefixedStringCharacters = setOf('b', 'c')
}
