package me.rerere.highlight.kotlin.languages.dockerfile

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.DelimitedRule
import me.rerere.highlight.kotlin.engine.rules.RegexRule
import me.rerere.highlight.kotlin.languages.bash.rules.ShellExpansionRule
import me.rerere.highlight.kotlin.languages.dockerfile.rules.DockerfileInstructionRule
import me.rerere.highlight.kotlin.languages.dockerfile.rules.DockerfileShellInstructionRule

internal fun createDockerfileRules(): List<GrammarRule> {
    return listOf(
        RegexRule(
            pattern = Regex("""\s+"""),
            scope = null,
        ),
        DelimitedRule(
            startDelimiter = "#",
            endDelimiter = null,
            scope = TokenScope.COMMENT,
            stopAtLineBreak = true,
            condition = { context -> context.isAtLineContentStart() },
        ),
        DockerfileShellInstructionRule,
        DockerfileInstructionRule,
        DelimitedRule(
            startDelimiter = "\"",
            endDelimiter = "\"",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            escapeCharacter = '\\',
            stopAtLineBreak = true,
        ),
        DelimitedRule(
            startDelimiter = "'",
            endDelimiter = "'",
            scope = TokenScope.STRING,
            nextKind = LexemeKind.Value,
            stopAtLineBreak = true,
        ),
        ShellExpansionRule,
        RegexRule(
            pattern = Regex("""(?:0[xX][0-9A-Fa-f]+|\d+(?:\.\d+)?)"""),
            scope = TokenScope.NUMBER,
            nextKind = LexemeKind.Value,
        ),
        RegexRule(
            pattern = Regex("""(?:&&|\|\||<<-?|>>|=|:|,|\[|\]|\{|\})"""),
            scope = TokenScope.OPERATOR,
            nextKind = LexemeKind.Operator,
        ),
    )
}

private fun me.rerere.highlight.kotlin.engine.MatchContext.isAtLineContentStart(): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && source[cursor] !in "\r\n") {
        if (source[cursor] != ' ' && source[cursor] != '\t') return false
        cursor--
    }
    return true
}
