package me.rerere.highlight.kotlin.languages.dockerfile.rules

import me.rerere.highlight.kotlin.engine.MatchContext

private val instructionPattern = Regex("""[A-Za-z]+""")

internal data class DockerfileInstructionMatch(
    val keyword: String,
    val endIndex: Int,
)

internal fun MatchContext.matchDockerfileInstruction(
    acceptedKeywords: Set<String>,
): DockerfileInstructionMatch? {
    if (!isAtDockerfileInstructionStart()) return null

    val instruction = instructionPattern.find(source, index)
        ?.takeIf { it.range.first == index }
        ?: return null
    val keyword = instruction.value.lowercase()
    if (keyword !in acceptedKeywords) return null

    val keywordEnd = instruction.range.last + 1
    if (keywordEnd > endIndex) return null
    if (keywordEnd < endIndex && !source[keywordEnd].isWhitespace()) return null
    return DockerfileInstructionMatch(
        keyword = keyword,
        endIndex = keywordEnd,
    )
}

private fun MatchContext.isAtDockerfileInstructionStart(): Boolean {
    var lineStart = index
    while (lineStart > 0 && source[lineStart - 1] !in "\r\n") lineStart--
    val prefix = source.substring(lineStart, index).trim()
    return prefix.isEmpty() || prefix.equals("onbuild", ignoreCase = true)
}
