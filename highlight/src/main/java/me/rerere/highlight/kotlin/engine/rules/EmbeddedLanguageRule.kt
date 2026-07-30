package me.rerere.highlight.kotlin.engine.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenEmitter

/**
 * Highlights one contiguous source range with another registered language.
 *
 * The locator owns host-language boundary detection. This rule owns language
 * resolution, safe fallback, token merging, and source preservation.
 */
internal class EmbeddedLanguageRule(
    private val locator: (MatchContext) -> EmbeddedLanguageRegion?,
) : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val region = locator(context) ?: return null
        require(region.endIndex > context.index) {
            "Embedded language rule must consume source at ${context.index}"
        }
        require(
            region.contentStartIndex in context.index..region.contentEndIndex &&
                region.contentEndIndex <= region.endIndex &&
                region.endIndex <= context.endIndex,
        ) {
            "Invalid embedded language range: ${context.index}.." +
                "${region.contentStartIndex}..${region.contentEndIndex}..${region.endIndex}"
        }

        val emitter = TokenEmitter()
        emitter.segment(
            source = context.source,
            startIndex = context.index,
            endIndex = region.contentStartIndex,
            scope = region.leadingScope,
        )
        emitter.appendAll(
            context.highlightEmbeddedRange(
                startIndex = region.contentStartIndex,
                endIndex = region.contentEndIndex,
                language = region.language,
            ).tokens,
        )
        emitter.segment(
            source = context.source,
            startIndex = region.contentEndIndex,
            endIndex = region.endIndex,
            scope = region.trailingScope,
        )
        return RuleMatch(
            endIndex = region.endIndex,
            tokens = emitter.build(),
            nextKind = region.nextKind,
        )
    }

    private fun TokenEmitter.segment(
        source: String,
        startIndex: Int,
        endIndex: Int,
        scope: String?,
    ) {
        if (startIndex == endIndex) return
        val content = source.substring(startIndex, endIndex)
        if (scope == null) plain(content) else token(content, scope)
    }
}

internal data class EmbeddedLanguageRegion(
    val language: String,
    val contentStartIndex: Int,
    val contentEndIndex: Int,
    val endIndex: Int = contentEndIndex,
    val leadingScope: String? = null,
    val trailingScope: String? = null,
    val nextKind: LexemeKind? = null,
)
