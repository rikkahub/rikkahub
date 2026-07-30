package me.rerere.highlight.kotlin.languages.sql.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object SqlDollarQuotedStringRule : GrammarRule {
    private val delimiterPattern = Regex("""\$[A-Za-z_][A-Za-z0-9_]*\$|\$\$""")

    override fun match(context: MatchContext): RuleMatch? {
        val delimiter = delimiterPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val contentStart = delimiter.range.last + 1
        val closingStart = context.source.indexOf(
            string = delimiter.value,
            startIndex = contentStart,
        ).takeIf { it in contentStart until context.endIndex }
        val endIndex = if (closingStart == null) {
            context.endIndex
        } else {
            (closingStart + delimiter.value.length).coerceAtMost(context.endIndex)
        }
        return context.tokenMatch(
            matchEndIndex = endIndex,
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
        )
    }
}
