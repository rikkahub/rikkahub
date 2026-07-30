package me.rerere.highlight.kotlin.languages.dockerfile.rules

import me.rerere.highlight.kotlin.engine.GrammarRule
import me.rerere.highlight.kotlin.engine.LexemeKind
import me.rerere.highlight.kotlin.engine.MatchContext
import me.rerere.highlight.kotlin.engine.RuleMatch
import me.rerere.highlight.kotlin.engine.TokenScope
import me.rerere.highlight.kotlin.engine.rules.EmbeddedLanguageRegion
import me.rerere.highlight.kotlin.engine.rules.EmbeddedLanguageRule
import me.rerere.highlight.kotlin.languages.dockerfile.DockerfileGrammar

internal object DockerfileShellInstructionRule : GrammarRule {
    private val heredocPattern = Regex(
        """<<(-)?[ \t]*(?:'([^'\r\n]+)'|"([^"\r\n]+)"|\\?([A-Za-z_][A-Za-z0-9_]*))""",
    )
    private val embeddedBash = EmbeddedLanguageRule(::locateEmbeddedBash)

    override fun match(context: MatchContext): RuleMatch? {
        return embeddedBash.match(context)
    }

    private fun locateEmbeddedBash(context: MatchContext): EmbeddedLanguageRegion? {
        val instruction = context.matchDockerfileInstruction(DockerfileGrammar.shellInstructions)
            ?: return null
        val logicalLineEnd = context.source.logicalLineEnd(instruction.endIndex, context.endIndex)
        val instructionEnd = context.source.heredocEnd(
            payloadStart = instruction.endIndex,
            logicalLineEnd = logicalLineEnd,
            endIndex = context.endIndex,
        )
        return EmbeddedLanguageRegion(
            language = "bash",
            contentStartIndex = instruction.endIndex,
            contentEndIndex = instructionEnd,
            leadingScope = TokenScope.KEYWORD,
            nextKind = LexemeKind.Value,
        )
    }

    private fun String.logicalLineEnd(startIndex: Int, endIndex: Int): Int {
        var lineEnd = indexOfLineEnd(startIndex, endIndex)
        while (lineEnd < endIndex && lineContinuesAt(lineEnd)) {
            lineEnd = indexOfLineEnd(afterLineEnd(lineEnd, endIndex), endIndex)
        }
        return lineEnd
    }

    private fun String.heredocEnd(
        payloadStart: Int,
        logicalLineEnd: Int,
        endIndex: Int,
    ): Int {
        val declaration = heredocPattern.find(this, payloadStart)
            ?.takeIf { it.range.first <= logicalLineEnd }
            ?: return logicalLineEnd
        val delimiter = declaration.groupValues.drop(2).firstOrNull { it.isNotEmpty() }
            ?: return logicalLineEnd
        val stripsTabs = declaration.groupValues[1].isNotEmpty()

        var lineStart = afterLineEnd(logicalLineEnd, endIndex)
        while (lineStart < endIndex) {
            val lineEnd = indexOfLineEnd(lineStart, endIndex)
            val line = substring(lineStart, lineEnd)
            val comparable = if (stripsTabs) line.trimStart('\t') else line
            if (comparable == delimiter) return lineEnd
            lineStart = afterLineEnd(lineEnd, endIndex)
        }
        return endIndex
    }

    private fun String.lineContinuesAt(lineEnd: Int): Boolean {
        var cursor = lineEnd - 1
        if (cursor >= 0 && this[cursor] == '\r') cursor--
        return cursor >= 0 && this[cursor] == '\\'
    }

    private fun String.indexOfLineEnd(startIndex: Int, endIndex: Int): Int {
        var cursor = startIndex
        while (cursor < endIndex && this[cursor] !in "\r\n") cursor++
        return cursor
    }

    private fun String.afterLineEnd(lineEnd: Int, endIndex: Int): Int {
        var cursor = lineEnd
        if (cursor < endIndex && this[cursor] == '\r') cursor++
        if (cursor < endIndex && this[cursor] == '\n') cursor++
        return cursor
    }
}
