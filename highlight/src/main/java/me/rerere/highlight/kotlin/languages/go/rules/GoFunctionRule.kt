package me.rerere.highlight.kotlin.languages.go.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.go.GoGrammar

internal object GoFunctionRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (!context.source.startsWith("func", context.index)) return null

        val keywordEnd = context.index + 4
        if (
            keywordEnd < context.endIndex &&
            GoGrammar.isIdentifierPart(context.source[keywordEnd])
        ) {
            return null
        }

        var cursor = skipWhitespace(context, keywordEnd)
        if (cursor < context.endIndex && context.source[cursor] == '(') {
            cursor = consumeReceiver(context, cursor) ?: keywordEnd
            cursor = skipWhitespace(context, cursor)
        }

        val nameStart = cursor
        if (
            nameStart >= context.endIndex ||
            !GoGrammar.isIdentifierStart(context.source[nameStart])
        ) {
            return context.tokenMatch(
                matchEndIndex = keywordEnd,
                scope = TokenScope.KEYWORD,
                nextKind = LexemeKind.Keyword,
            )
        }

        cursor++
        while (cursor < context.endIndex && GoGrammar.isIdentifierPart(context.source[cursor])) {
            cursor++
        }

        val emitter = TokenEmitter()
        emitter.token("func", TokenScope.KEYWORD)
        emitter.appendAll(context.highlightRange(keywordEnd, nameStart).tokens)
        emitter.token(context.source.substring(nameStart, cursor), TokenScope.FUNCTION)
        return RuleMatch(
            endIndex = cursor,
            tokens = emitter.build(),
            nextKind = LexemeKind.Value,
        )
    }

    private fun skipWhitespace(context: MatchContext, startIndex: Int): Int {
        var cursor = startIndex
        while (cursor < context.endIndex && context.source[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun consumeReceiver(context: MatchContext, startIndex: Int): Int? {
        var cursor = startIndex
        var depth = 0
        while (cursor < context.endIndex) {
            when (context.source[cursor]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return cursor + 1
                }
            }
            cursor++
        }
        return null
    }
}
