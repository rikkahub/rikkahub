package me.rerere.ai.provider.providers.openai

internal fun Long?.normalizedSeedOrNull(): Long? = this?.takeIf { it >= 0L }

internal fun Int?.normalizedTopKOrNull(): Int? = this?.takeIf { it >= 0 }

internal fun Float?.normalizedNonNegativeOrNull(): Float? = this?.takeIf { it >= 0f }

internal fun String?.normalizedOpenAIVerbosityOrNull(): String? {
    val normalized = this?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it == "low" || it == "medium" || it == "high" }
}
