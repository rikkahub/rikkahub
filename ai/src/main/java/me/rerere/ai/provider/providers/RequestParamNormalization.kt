package me.rerere.ai.provider.providers

internal fun List<String>.normalizedStopSequencesOrNull(): List<String>? {
    val normalized = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return normalized.takeIf { it.isNotEmpty() }
}

internal fun String?.normalizedNonBlankOrNull(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}
