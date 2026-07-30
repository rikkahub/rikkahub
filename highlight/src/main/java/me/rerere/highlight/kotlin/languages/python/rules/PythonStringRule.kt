package me.rerere.highlight.kotlin.languages.python.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object PythonStringRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val start = parseStart(context) ?: return null
        val emitter = TokenEmitter()
        var cursor = context.index + start.prefixLength + start.delimiter.length
        var stringStart = context.index

        while (cursor < context.endIndex) {
            when {
                context.source.startsWith(start.delimiter, cursor) -> {
                    cursor += start.delimiter.length
                    emitter.string(context, stringStart, cursor)
                    return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
                }
                context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                !start.isTriple &&
                    (context.source[cursor] == '\n' || context.source[cursor] == '\r') -> break
                start.isFormatted && context.source.startsWith("{{", cursor) -> cursor += 2
                start.isFormatted && context.source.startsWith("}}", cursor) -> cursor += 2
                start.isFormatted && context.source[cursor] == '{' -> {
                    emitter.string(context, stringStart, cursor)
                    emitter.token("{", TokenScope.PUNCTUATION)
                    cursor++

                    val expression = context.highlightBalanced(cursor)
                    emitter.appendAll(expression.tokens)
                    cursor = expression.endIndex
                    if (cursor < context.endIndex && context.source[cursor] == '}') {
                        emitter.token("}", TokenScope.PUNCTUATION)
                        cursor++
                    }
                    stringStart = cursor
                }
                else -> cursor++
            }
        }

        emitter.string(context, stringStart, cursor)
        return RuleMatch(cursor, emitter.build(), LexemeKind.Value)
    }

    private fun parseStart(context: MatchContext): StringStart? {
        val source = context.source
        val index = context.index
        var prefixLength = 0

        while (
            prefixLength < 2 &&
            index + prefixLength < context.endIndex &&
            source[index + prefixLength].lowercaseChar() in prefixCharacters
        ) {
            prefixLength++
        }

        for (candidateLength in prefixLength downTo 0) {
            val prefix = source.substring(index, index + candidateLength).lowercase()
            if (prefix !in validPrefixes) continue

            val quoteIndex = index + candidateLength
            val quote = source.getOrNull(quoteIndex)
            if (quote != '\'' && quote != '"') continue

            val triple = buildString { repeat(3) { append(quote) } }
            val isTriple = source.startsWith(triple, quoteIndex)
            return StringStart(
                prefixLength = candidateLength,
                delimiter = if (isTriple) triple else quote.toString(),
                isTriple = isTriple,
                isFormatted = 'f' in prefix || 't' in prefix,
            )
        }
        return null
    }

    private fun TokenEmitter.string(
        context: MatchContext,
        startIndex: Int,
        endIndex: Int,
    ) {
        token(
            content = context.source.substring(startIndex, endIndex),
            type = TokenScope.STRING,
        )
    }

    private data class StringStart(
        val prefixLength: Int,
        val delimiter: String,
        val isTriple: Boolean,
        val isFormatted: Boolean,
    )

    private val prefixCharacters = setOf('b', 'f', 'r', 't', 'u')
    private val validPrefixes = setOf(
        "", "b", "f", "r", "t", "u", "br", "fr", "rb", "rf", "rt", "tr",
    )
}
