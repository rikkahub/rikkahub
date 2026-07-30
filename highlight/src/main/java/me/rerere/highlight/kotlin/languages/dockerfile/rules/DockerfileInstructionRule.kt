package me.rerere.highlight.kotlin.languages.dockerfile.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.languages.dockerfile.DockerfileGrammar

internal object DockerfileInstructionRule : GrammarRule {
    override fun match(context: MatchContext): RuleMatch? {
        val instruction = context.matchDockerfileInstruction(DockerfileGrammar.instructions)
            ?: return null
        return context.tokenMatch(
            matchEndIndex = instruction.endIndex,
            scope = TokenScope.KEYWORD,
            nextKind = LexemeKind.Keyword,
        )
    }
}
