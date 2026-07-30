package me.rerere.highlight.kotlin.languages.bash.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter
import me.rerere.highlight.kotlin.engine.TokenScope

internal object ShellExpansionRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val expansion = ShellExpansionParser.parse(context, context.index) ?: return null
        return RuleMatch(
            endIndex = expansion.endIndex,
            tokens = expansion.tokens,
            nextKind = LexemeKind.Value,
        )
    }
}

internal data class ParsedExpansion(
    val endIndex: Int,
    val tokens: List<me.rerere.highlight.HighlightToken>,
)

internal object ShellExpansionParser {
    private val specialParameters = setOf('#', '@', '*', '?', '!', '$', '-', '0')

    fun parse(context: MatchContext, startIndex: Int): ParsedExpansion? {
        if (context.source.getOrNull(startIndex) != '$') return null

        return when {
            context.source.startsWith("\$((", startIndex) -> parseArithmetic(context, startIndex)
            context.source.startsWith("\$(", startIndex) -> parseCommand(context, startIndex)
            context.source.startsWith("\${", startIndex) -> parseBracedVariable(context, startIndex)
            else -> parseSimpleVariable(context, startIndex)
        }
    }

    private fun parseArithmetic(context: MatchContext, startIndex: Int): ParsedExpansion {
        val closingStart = findClosingParentheses(
            context = context,
            contentStart = startIndex + 3,
            initialDepth = 2,
        )
        val endIndex = if (closingStart == null) context.endIndex else closingStart + 2
        val innerEnd = closingStart ?: context.endIndex
        val emitter = TokenEmitter()
        emitter.token("\$((", TokenScope.PUNCTUATION)
        emitter.appendAll(context.highlightRange(startIndex + 3, innerEnd).tokens)
        if (closingStart != null) emitter.token("))", TokenScope.PUNCTUATION)
        return ParsedExpansion(endIndex, emitter.build())
    }

    private fun parseCommand(context: MatchContext, startIndex: Int): ParsedExpansion {
        val closingStart = findClosingParentheses(
            context = context,
            contentStart = startIndex + 2,
            initialDepth = 1,
        )
        val endIndex = if (closingStart == null) context.endIndex else closingStart + 1
        val innerEnd = closingStart ?: context.endIndex
        val emitter = TokenEmitter()
        emitter.token("\$(", TokenScope.PUNCTUATION)
        emitter.appendAll(context.highlightRange(startIndex + 2, innerEnd).tokens)
        if (closingStart != null) emitter.token(")", TokenScope.PUNCTUATION)
        return ParsedExpansion(endIndex, emitter.build())
    }

    private fun parseBracedVariable(context: MatchContext, startIndex: Int): ParsedExpansion {
        var cursor = startIndex + 2
        var depth = 1
        while (cursor < context.endIndex) {
            when {
                context.source[cursor] == '\\' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                context.source.startsWith("\${", cursor) -> {
                    depth++
                    cursor += 2
                }
                context.source[cursor] == '}' -> {
                    depth--
                    cursor++
                    if (depth == 0) break
                }
                else -> cursor++
            }
        }
        return ParsedExpansion(
            endIndex = cursor,
            tokens = listOf(context.scopedToken(startIndex, cursor, TokenScope.VARIABLE)),
        )
    }

    private fun parseSimpleVariable(
        context: MatchContext,
        startIndex: Int,
    ): ParsedExpansion? {
        val first = context.source.getOrNull(startIndex + 1) ?: return null
        var cursor = startIndex + 1
        when {
            first == '_' || first.isLetter() -> {
                cursor++
                while (
                    cursor < context.endIndex &&
                    (context.source[cursor] == '_' || context.source[cursor].isLetterOrDigit())
                ) {
                    cursor++
                }
            }
            first.isDigit() || first in specialParameters -> cursor++
            else -> return null
        }
        return ParsedExpansion(
            endIndex = cursor,
            tokens = listOf(context.scopedToken(startIndex, cursor, TokenScope.VARIABLE)),
        )
    }

    private fun findClosingParentheses(
        context: MatchContext,
        contentStart: Int,
        initialDepth: Int,
    ): Int? {
        var cursor = contentStart
        var depth = initialDepth
        var quote: Char? = null
        while (cursor < context.endIndex) {
            val char = context.source[cursor]
            when {
                char == '\\' && quote != '\'' -> {
                    cursor = (cursor + 2).coerceAtMost(context.endIndex)
                }
                quote != null -> {
                    if (char == quote) quote = null
                    cursor++
                }
                char == '\'' || char == '"' || char == '`' -> {
                    quote = char
                    cursor++
                }
                char == '(' -> {
                    depth++
                    cursor++
                }
                char == ')' -> {
                    depth--
                    if (depth == 0) return cursor
                    if (depth == 1 && initialDepth == 2) {
                        if (context.source.getOrNull(cursor + 1) == ')') return cursor
                        depth++
                    }
                    cursor++
                }
                else -> cursor++
            }
        }
        return null
    }

    private fun MatchContext.scopedToken(
        startIndex: Int,
        endIndex: Int,
        scope: String,
    ): me.rerere.highlight.HighlightToken {
        val content = source.substring(startIndex, endIndex)
        return me.rerere.highlight.HighlightToken.Token.StringContent(
            content = content,
            type = scope,
            length = content.length,
        )
    }
}
