package me.rerere.highlight.kotlin.languages.yaml.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object YamlBlockScalarRule : GrammarRule {
    private val headerPattern = Regex("""[|>](?:[1-9][+-]?|[+-][1-9]?)?[ \t]*(?:#[^\r\n]*)?""")

    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '|' && context.source[context.index] != '>') {
            return null
        }
        val header = headerPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val headerEnd = header.range.last + 1
        if (headerEnd > context.endIndex) return null
        if (headerEnd < context.endIndex && context.source[headerEnd] !in "\r\n") return null

        val contentStart = context.source.afterLineEnd(headerEnd, context.endIndex)
        if (contentStart == headerEnd) {
            return context.tokenMatch(headerEnd, TokenScope.STRING, LexemeKind.Value)
        }

        val baseIndent = context.source.lineIndentAt(context.index)
        val explicitIndent = header.value.firstOrNull(Char::isDigit)?.digitToInt()
        val requiredIndent = explicitIndent?.let(baseIndent::plus)
            ?: context.source.firstContentIndent(contentStart, context.endIndex, baseIndent)
            ?: return context.tokenMatch(contentStart, TokenScope.STRING, LexemeKind.Value)

        var cursor = contentStart
        while (cursor < context.endIndex) {
            val lineEnd = context.source.indexOfLineEnd(cursor, context.endIndex)
            val isBlank = context.source.substring(cursor, lineEnd).isBlank()
            val indent = context.source.lineIndentAt(cursor)
            if (!isBlank && indent < requiredIndent) break
            cursor = context.source.afterLineEnd(lineEnd, context.endIndex)
        }

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }

    private fun String.firstContentIndent(
        startIndex: Int,
        endIndex: Int,
        baseIndent: Int,
    ): Int? {
        var cursor = startIndex
        while (cursor < endIndex) {
            val lineEnd = indexOfLineEnd(cursor, endIndex)
            if (substring(cursor, lineEnd).isNotBlank()) {
                return lineIndentAt(cursor).takeIf { it > baseIndent }
            }
            cursor = afterLineEnd(lineEnd, endIndex)
        }
        return null
    }

    private fun String.lineIndentAt(index: Int): Int {
        var lineStart = index
        while (lineStart > 0 && this[lineStart - 1] !in "\r\n") lineStart--

        var cursor = lineStart
        var indent = 0
        while (cursor < length) {
            when (this[cursor]) {
                ' ' -> indent++
                '\t' -> indent += 2
                else -> return indent
            }
            cursor++
        }
        return indent
    }

    private fun String.indexOfLineEnd(startIndex: Int, endIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && this[cursor] !in "\r\n") cursor++
        return cursor
    }

    private fun String.afterLineEnd(lineEnd: Int, endIndex: Int): Int {
        var cursor = lineEnd
        if (cursor < endIndex && this[cursor] == '\r') cursor++
        if (cursor < endIndex && this[cursor] == '\n') cursor++
        return cursor
    }
}
