package me.rerere.highlight.kotlin.languages.yaml.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope

internal object YamlKeyRule : GrammarRule {
    private val keyPattern = Regex(
        """(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|<<|[\w*@][\w*@ :()./\-]*):(?=[ \t\r\n]|$)""",
    )

    override fun match(context: MatchContext): RuleMatch? {
        val match = keyPattern.find(context.source, context.index)
            ?.takeIf { it.range.first == context.index }
            ?: return null
        val endIndex = match.range.last + 1
        if (endIndex > context.endIndex) return null

        return context.tokenMatch(
            matchEndIndex = endIndex,
            scope = TokenScope.ATTR_NAME,
            nextKind = LexemeKind.PropertyAccess,
        )
    }
}
