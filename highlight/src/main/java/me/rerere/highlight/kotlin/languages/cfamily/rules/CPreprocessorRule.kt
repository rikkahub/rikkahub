package me.rerere.highlight.kotlin.languages.cfamily.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object CPreprocessorRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '#' || !isAtLineIndent(context)) return null

        var directiveEnd = context.index + 1
        while (
            directiveEnd < context.endIndex &&
            context.source[directiveEnd].isWhitespace() &&
            context.source[directiveEnd] != '\n' &&
            context.source[directiveEnd] != '\r'
        ) {
            directiveEnd++
        }
        val nameStart = directiveEnd
        while (directiveEnd < context.endIndex && context.source[directiveEnd].isLetter()) {
            directiveEnd++
        }
        if (directiveEnd == nameStart) return null

        val lineEnd = findLogicalLineEnd(context, directiveEnd)
        val directive = context.source.substring(nameStart, directiveEnd).lowercase()
        val emitter = TokenEmitter()
        emitter.token(
            context.source.substring(context.index, directiveEnd),
            TokenScope.IMPORTANT,
        )

        val headerStart = context.nextNonWhitespace(directiveEnd, lineEnd)
        if (
            directive == "include" &&
            headerStart < lineEnd &&
            context.source[headerStart] == '<'
        ) {
            val headerEnd = context.source.indexOf('>', headerStart + 1)
                .takeIf { it in (headerStart + 1) until lineEnd }
                ?.plus(1)
            if (headerEnd != null) {
                emitter.appendAll(context.highlightRange(directiveEnd, headerStart).tokens)
                emitter.token(
                    context.source.substring(headerStart, headerEnd),
                    TokenScope.STRING,
                )
                emitter.appendAll(context.highlightRange(headerEnd, lineEnd).tokens)
            } else {
                emitter.appendAll(context.highlightRange(directiveEnd, lineEnd).tokens)
            }
        } else {
            emitter.appendAll(context.highlightRange(directiveEnd, lineEnd).tokens)
        }

        return RuleMatch(
            endIndex = lineEnd,
            tokens = emitter.build(),
            nextKind = LexemeKind.Keyword,
        )
    }

    private fun isAtLineIndent(context: MatchContext): Boolean {
        var cursor = context.index - 1
        while (cursor >= 0 && context.source[cursor] != '\n' && context.source[cursor] != '\r') {
            if (!context.source[cursor].isWhitespace()) return false
            cursor--
        }
        return true
    }

    private fun findLogicalLineEnd(context: MatchContext, startIndex: Int): Int {
        var cursor = startIndex
        while (cursor < context.endIndex) {
            if (context.source[cursor] == '\n' || context.source[cursor] == '\r') {
                var previous = cursor - 1
                if (
                    context.source[cursor] == '\n' &&
                    previous >= startIndex &&
                    context.source[previous] == '\r'
                ) {
                    previous--
                }
                if (previous >= startIndex && context.source[previous] == '\\') {
                    cursor++
                    if (
                        context.source[cursor - 1] == '\r' &&
                        cursor < context.endIndex &&
                        context.source[cursor] == '\n'
                    ) {
                        cursor++
                    }
                    continue
                }
                break
            }
            cursor++
        }
        return cursor
    }

    private fun MatchContext.nextNonWhitespace(startIndex: Int, limit: Int): Int {
        var cursor = startIndex
        while (cursor < limit && source[cursor].isWhitespace()) cursor++
        return cursor
    }
}
