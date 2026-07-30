package me.rerere.highlight.kotlin.languages.css.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.css.isInDeclarationValue

internal object CssHashRule : GrammarRule {
    private val pattern = Regex("""#[A-Za-z0-9_-]+""")
    private val colorPattern = Regex("""#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{4}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})""")

    override fun match(context: MatchContext): RuleMatch? {
        val match = pattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val endIndex = match.range.last + 1
        if (endIndex > context.endIndex) return null

        val isColor = colorPattern.matches(match.value) && context.isInDeclarationValue()
        return context.tokenMatch(
            matchEndIndex = endIndex,
            scope = if (isColor) TokenScope.NUMBER else TokenScope.CONSTANT,
            nextKind = LexemeKind.Value,
        )
    }
}
