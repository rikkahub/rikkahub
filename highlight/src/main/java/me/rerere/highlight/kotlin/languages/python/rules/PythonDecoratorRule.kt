package me.rerere.highlight.kotlin.languages.python.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.python.PythonGrammar

internal object PythonDecoratorRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        if (context.source[context.index] != '@' || !isAtLineIndent(context)) return null

        var cursor = context.index + 1
        if (
            cursor >= context.endIndex ||
            !PythonGrammar.isIdentifierStart(context.source[cursor])
        ) {
            return null
        }

        cursor = consumeIdentifier(context, cursor)
        while (
            cursor + 1 < context.endIndex &&
            context.source[cursor] == '.' &&
            PythonGrammar.isIdentifierStart(context.source[cursor + 1])
        ) {
            cursor = consumeIdentifier(context, cursor + 1)
        }

        return context.tokenMatch(
            matchEndIndex = cursor,
            scope = TokenScope.IMPORTANT,
            nextKind = LexemeKind.Value,
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

    private fun consumeIdentifier(context: MatchContext, startIndex: Int): Int {
        var cursor = startIndex + 1
        while (
            cursor < context.endIndex &&
            PythonGrammar.isIdentifierPart(context.source[cursor])
        ) {
            cursor++
        }
        return cursor
    }
}
