package me.rerere.rikkahub.data.model

import kotlin.text.RegexOption

private val ST_REGEX_SOURCE_PATTERN = Regex("""^(\/?)(.+)\1([a-zA-Z]*)$""", setOf(RegexOption.DOT_MATCHES_ALL))
private val ST_REGEX_VALID_FLAGS = Regex("""^(?!.*?(.).*?\1)[gmixXsuUAJ]*$""")
private val ST_REGEX_DELIMITED_PATTERN = Regex("""^/(.+)/([a-zA-Z]*)$""", setOf(RegexOption.DOT_MATCHES_ALL))

data class AssistantRegexPatternSpec(
    val pattern: String,
    val options: Set<RegexOption>,
    val replaceAll: Boolean,
)

fun AssistantRegex.exportFindRegex(): String {
    return rawFindRegex.ifBlank { findRegex }
}

fun AssistantRegex.editableFindRegex(): String {
    return rawFindRegex.ifBlank { findRegex }
}

fun AssistantRegex.withFindRegexInput(input: String): AssistantRegex {
    val trimmedInput = input.trim()
    return copy(
        findRegex = normalizeAssistantRegexPattern(trimmedInput).orEmpty(),
        rawFindRegex = trimmedInput,
    )
}

fun normalizeAssistantRegexPattern(input: String): String? {
    return resolveAssistantRegexPatternSpec(source = input, stCompatible = true)?.pattern
}

internal fun AssistantRegex.shouldUseStRegexCompatibility(): Boolean {
    if (sourceKind != AssistantRegexSourceKind.MANUAL) return true
    if (stPlacements.isNotEmpty()) return true
    val source = exportFindRegex()
    return source.isNotBlank() && ST_REGEX_DELIMITED_PATTERN.matches(source)
}

internal fun resolveAssistantRegexPatternSpec(
    source: String,
    stCompatible: Boolean,
): AssistantRegexPatternSpec? {
    if (source.isBlank()) return null
    if (!stCompatible) {
        return AssistantRegexPatternSpec(
            pattern = source,
            options = emptySet(),
            replaceAll = true,
        )
    }

    val match = ST_REGEX_SOURCE_PATTERN.matchEntire(source)
    if (match == null) {
        return AssistantRegexPatternSpec(
            pattern = source,
            options = emptySet(),
            replaceAll = false,
        )
    }

    val pattern = match.groupValues[2]
    val flags = match.groupValues[3]
    if (flags.isNotEmpty() && !ST_REGEX_VALID_FLAGS.matches(flags)) {
        return AssistantRegexPatternSpec(
            pattern = source,
            options = emptySet(),
            replaceAll = false,
        )
    }

    val options = buildSet {
        if ('i' in flags) add(RegexOption.IGNORE_CASE)
        if ('m' in flags) add(RegexOption.MULTILINE)
        if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        if ('x' in flags || 'X' in flags) add(RegexOption.COMMENTS)
    }
    return AssistantRegexPatternSpec(
        pattern = pattern,
        options = options,
        replaceAll = 'g' in flags,
    )
}
