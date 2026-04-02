package me.rerere.rikkahub.data.ai.transformers

internal data class IfBranches(
    val thenBranch: String,
    val elseBranch: String?,
)

internal data class InlineIfArguments(
    val condition: String,
    val content: String,
)

internal data class ScopedMacroMatch(
    val open: MacroTag,
    val close: MacroTag,
)

internal data class MacroTag(
    val raw: String,
    val name: String,
    val args: List<String>,
    val startIndex: Int,
    val endIndex: Int,
    val isClosing: Boolean,
) {
    fun isScopedOpening(): Boolean {
        return when (name) {
            "if" -> !isClosing && args.size <= 1
            "trim" -> !isClosing
            "//" -> !isClosing
            else -> false
        }
    }

    fun isScopedOpeningIf(): Boolean {
        return name == "if" && !isClosing && args.size <= 1
    }

    companion object {
        fun from(match: MatchResult): MacroTag? {
            val raw = match.value
            val body = match.groupValues[1].trim()
            if (body.isEmpty()) return null

            val isClosing = body.startsWith("/")
            val normalizedBody = if (isClosing) body.removePrefix("/").trim() else body
            val parsed = ParsedMacro.parse(normalizedBody) ?: return null
            return MacroTag(
                raw = raw,
                name = parsed.name.lowercase(),
                args = parsed.args,
                startIndex = match.range.first,
                endIndex = match.range.last,
                isClosing = isClosing,
            )
        }
    }
}

internal data class ParsedMacro(
    val name: String,
    val args: List<String>,
) {
    companion object {
        fun parse(body: String): ParsedMacro? {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return null

            val delimiterIndex = listOf(
                trimmed.indexOf("::").takeIf { it >= 0 },
                trimmed.indexOfAny(charArrayOf(' ', '\t', '\r', '\n')).takeIf { it >= 0 },
            ).filterNotNull().minOrNull() ?: trimmed.length

            val name = trimmed.substring(0, delimiterIndex)
            val remainder = trimmed.substring(delimiterIndex)
            val args = when {
                remainder.startsWith("::") -> remainder.removePrefix("::").split("::")
                remainder.isNotBlank() -> listOf(remainder.trim())
                else -> emptyList()
            }
            return ParsedMacro(name = name, args = args)
        }
    }
}
