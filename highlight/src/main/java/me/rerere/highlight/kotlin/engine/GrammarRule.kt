package me.rerere.highlight.kotlin.engine

import me.rerere.highlight.HighlightToken

/**
 * One reusable lexical rule in a language grammar.
 *
 * A rule must only return a match that starts at [MatchContext.index].
 */
internal fun interface GrammarRule {
    fun match(context: MatchContext): RuleMatch?
}

internal data class RuleMatch(
    val endIndex: Int,
    val tokens: List<HighlightToken>,
    val nextKind: LexemeKind? = null,
)

internal data class ScanResult(
    val tokens: List<HighlightToken>,
    val endIndex: Int,
)

internal class MatchContext(
    val source: String,
    val index: Int,
    val endIndex: Int,
    val previousKind: LexemeKind,
    val language: LanguageDefinition,
    private val engine: GrammarEngine,
) {
    fun tokenMatch(
        matchEndIndex: Int,
        scope: String?,
        nextKind: LexemeKind? = null,
    ): RuleMatch {
        val content = source.substring(index, matchEndIndex)
        val token = if (scope == null) {
            HighlightToken.Plain(content)
        } else {
            HighlightToken.Token.StringContent(
                content = content,
                type = scope,
                length = content.length,
            )
        }
        return RuleMatch(
            endIndex = matchEndIndex,
            tokens = listOf(token),
            nextKind = nextKind,
        )
    }

    fun highlightBalanced(startIndex: Int): ScanResult {
        return engine.highlightBalanced(
            source = source,
            startIndex = startIndex,
            endIndex = endIndex,
            language = language,
        )
    }

    fun highlightRange(startIndex: Int, endIndex: Int): ScanResult {
        require(startIndex in index..endIndex && endIndex <= this.endIndex)
        return engine.highlightRange(
            source = source,
            startIndex = startIndex,
            endIndex = endIndex,
            language = language,
        )
    }
}

internal enum class LexemeKind(val canStartExpression: Boolean) {
    Start(true),
    OpeningDelimiter(true),
    Operator(true),
    KeywordExpressionStarter(true),
    Keyword(true),
    ClassDeclaration(true),
    PropertyAccess(false),
    Value(false),
}
