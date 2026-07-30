package me.rerere.highlight.kotlin.languages.bash.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object BashHeredocRule : GrammarRule {
    private val declarationPattern = Regex(
        """<<(-)?[ \t]*(?:'([^'\r\n]+)'|"([^"\r\n]+)"|\\?([A-Za-z_][A-Za-z0-9_]*))""",
    )

    override fun match(context: MatchContext): RuleMatch? {
        if (
            !context.source.startsWith("<<", context.index) ||
            context.source.startsWith("<<<", context.index)
        ) {
            return null
        }
        val declaration = declarationPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val declarationEnd = declaration.range.last + 1
        if (declarationEnd > context.endIndex) return null

        val delimiter = declaration.groupValues.drop(2).firstOrNull { it.isNotEmpty() }
            ?: return null
        val stripsTabs = declaration.groupValues[1].isNotEmpty()
        val firstLineEnd = context.source.indexOfLineEnd(declarationEnd, context.endIndex)
        val emitter = TokenEmitter()
        emitter.token(
            context.source.substring(context.index, declarationEnd),
            TokenScope.OPERATOR,
        )
        if (firstLineEnd == context.endIndex) {
            return RuleMatch(declarationEnd, emitter.build(), LexemeKind.Operator)
        }

        val bodyStart = context.source.afterLineEnd(firstLineEnd, context.endIndex)
        emitter.plain(context.source.substring(declarationEnd, bodyStart))
        var lineStart = bodyStart
        var bodyEnd = context.endIndex
        while (lineStart < context.endIndex) {
            val lineEnd = context.source.indexOfLineEnd(lineStart, context.endIndex)
            val line = context.source.substring(lineStart, lineEnd)
            val comparable = if (stripsTabs) line.trimStart('\t') else line
            if (comparable == delimiter) {
                bodyEnd = lineEnd
                break
            }
            lineStart = context.source.afterLineEnd(lineEnd, context.endIndex)
        }
        emitter.token(
            context.source.substring(bodyStart, bodyEnd),
            TokenScope.STRING,
        )
        return RuleMatch(bodyEnd, emitter.build(), LexemeKind.Value)
    }

    private fun String.indexOfLineEnd(startIndex: Int, endIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && this[cursor] != '\n' && this[cursor] != '\r') cursor++
        return cursor
    }

    private fun String.afterLineEnd(lineEnd: Int, endIndex: Int): Int {
        var cursor = lineEnd
        if (cursor < endIndex && this[cursor] == '\r') cursor++
        if (cursor < endIndex && this[cursor] == '\n') cursor++
        return cursor
    }
}
