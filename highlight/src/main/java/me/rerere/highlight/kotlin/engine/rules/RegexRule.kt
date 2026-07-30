package me.rerere.highlight.kotlin.engine.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch

internal class RegexRule(
    private val pattern: Regex,
    private val scope: (MatchContext, MatchResult) -> String?,
    private val nextKind: (MatchContext, MatchResult) -> LexemeKind? = { _, _ -> null },
    private val condition: (MatchContext) -> Boolean = { true },
) : GrammarRule {
    constructor(
        pattern: Regex,
        scope: String?,
        nextKind: LexemeKind? = null,
        condition: (MatchContext) -> Boolean = { true },
    ) : this(
        pattern = pattern,
        scope = { _, _ -> scope },
        nextKind = { _, _ -> nextKind },
        condition = condition,
    )

    override fun match(context: MatchContext): RuleMatch? {
        if (!condition(context)) return null

        val match = pattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val matchEndIndex = match.range.last + 1
        if (matchEndIndex > context.endIndex) return null

        return context.tokenMatch(
            matchEndIndex = matchEndIndex,
            scope = scope(context, match),
            nextKind = nextKind(context, match),
        )
    }
}
