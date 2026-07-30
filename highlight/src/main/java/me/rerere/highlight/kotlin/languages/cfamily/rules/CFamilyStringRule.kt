package me.rerere.highlight.kotlin.languages.cfamily.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object CFamilyStringRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val start = parseStart(context) ?: return null
        return if (start.rawDelimiter != null) {
            matchRawString(context, start)
        } else {
            matchEscapedString(context, start)
        }
    }

    private fun matchEscapedString(
        context: MatchContext,
        start: StringStart,
    ): RuleMatch {
        var cursor = start.contentStart
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '\\' -> cursor = (cursor + 2).coerceAtMost(context.endIndex)
                start.quote -> {
                    cursor++
                    break
                }
                '\n', '\r' -> break
                else -> cursor++
            }
        }
        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }

    private fun matchRawString(
        context: MatchContext,
        start: StringStart,
    ): RuleMatch {
        val closingDelimiter = ")${start.rawDelimiter}\""
        val closingIndex = context.source.indexOf(
            string = closingDelimiter,
            startIndex = start.contentStart,
        ).takeIf { it in start.contentStart until context.endIndex }
        val endIndex = closingIndex
            ?.plus(closingDelimiter.length)
            ?.coerceAtMost(context.endIndex)
            ?: context.endIndex
        return context.tokenMatch(
            matchEndIndex = endIndex,
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }

    private fun parseStart(context: MatchContext): StringStart? {
        val source = context.source
        var cursor = context.index
        when {
            source.startsWith("u8", cursor) -> cursor += 2
            source.getOrNull(cursor) in prefixCharacters -> cursor++
        }

        if (source.getOrNull(cursor) == 'R' && source.getOrNull(cursor + 1) == '"') {
            val delimiterStart = cursor + 2
            var delimiterEnd = delimiterStart
            while (
                delimiterEnd < context.endIndex &&
                delimiterEnd - delimiterStart <= 16 &&
                source[delimiterEnd] != '('
            ) {
                if (source[delimiterEnd].isWhitespace() || source[delimiterEnd] in invalidRawChars) {
                    return null
                }
                delimiterEnd++
            }
            if (
                delimiterEnd >= context.endIndex ||
                delimiterEnd - delimiterStart > 16 ||
                source[delimiterEnd] != '('
            ) {
                return null
            }
            return StringStart(
                quote = '"',
                contentStart = delimiterEnd + 1,
                rawDelimiter = source.substring(delimiterStart, delimiterEnd),
            )
        }

        val quote = source.getOrNull(cursor)
        if (quote != '"' && quote != '\'') return null
        return StringStart(
            quote = quote,
            contentStart = cursor + 1,
            rawDelimiter = null,
        )
    }

    private data class StringStart(
        val quote: Char,
        val contentStart: Int,
        val rawDelimiter: String?,
    )

    private val prefixCharacters = setOf('u', 'U', 'L')
    private val invalidRawChars = setOf('(', ')', '\\', '"')
}
