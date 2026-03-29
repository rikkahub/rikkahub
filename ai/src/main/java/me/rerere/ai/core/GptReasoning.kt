package me.rerere.ai.core

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility

private enum class GptReasoningBucket(
    val supportedLevels: List<ReasoningLevel>
) {
    GPT_5(listOf(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)),
    GPT_5_PRO(listOf(ReasoningLevel.HIGH)),
    GPT_5_CODEX(listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)),
    GPT_5_1(listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)),
    GPT_5_1_CODEX(listOf(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)),
    GPT_5_1_CODEX_MAX(listOf(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH)),
    GPT_5_2_PLUS_BASE(listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH)),
    GPT_5_2_PLUS_PRO(listOf(ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH)),
    GPT_5_2_PLUS_CODEX(listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH)),
}

private val reasoningPriority = listOf(
    ReasoningLevel.OFF,
    ReasoningLevel.MINIMAL,
    ReasoningLevel.LOW,
    ReasoningLevel.MEDIUM,
    ReasoningLevel.HIGH,
    ReasoningLevel.XHIGH,
)

private val reasoningPriorityByLevel = reasoningPriority
    .withIndex()
    .associate { (index, level) -> level to index }

private val gpt5Regex = Regex("^gpt-5(?:\\.(\\d+))?(?:-([a-z0-9.-]+))?$")
private val snapshotSuffixRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")

fun getSupportedGptReasoningLevels(modelId: String): List<ReasoningLevel>? {
    return getGptReasoningBucket(modelId)?.supportedLevels
}

fun isGptReasoningModel(modelId: String): Boolean {
    return getSupportedGptReasoningLevels(modelId) != null
}

fun resolveGptReasoningLevel(modelId: String, thinkingBudget: Int?): ReasoningLevel? {
    val supportedLevels = getSupportedGptReasoningLevels(modelId) ?: return null
    val requestedLevel = ReasoningLevel.fromBudgetTokens(thinkingBudget)
    if (requestedLevel == ReasoningLevel.AUTO) return ReasoningLevel.AUTO
    return clampReasoningLevel(requestedLevel, supportedLevels)
}

fun getGptReasoningEffort(modelId: String, thinkingBudget: Int?): String? {
    val resolvedLevel = resolveGptReasoningLevel(modelId, thinkingBudget) ?: return null
    return resolvedLevel.takeUnless { it == ReasoningLevel.AUTO }?.effort
}

fun resolveCompatibilityReasoningLevel(thinkingBudget: Int?): ReasoningLevel {
    return ReasoningLevel.fromCompatibilityBudgetTokens(thinkingBudget)
}

fun normalizeStoredThinkingBudget(modelId: String?, thinkingBudget: Int?): Int? {
    if (thinkingBudget == null || modelId == null || isGptReasoningModel(modelId)) return thinkingBudget

    return when (thinkingBudget) {
        ReasoningLevel.MINIMAL.budgetTokens -> ReasoningLevel.LOW.budgetTokens
        ReasoningLevel.XHIGH.budgetTokens -> ReasoningLevel.HIGH.budgetTokens
        else -> thinkingBudget
    }
}

fun Model.supportsReasoningConfiguration(): Boolean {
    return abilities.contains(ModelAbility.REASONING) || isGptReasoningModel(modelId)
}

private fun getGptReasoningBucket(modelId: String): GptReasoningBucket? {
    val normalizedModelId = normalizeModelId(modelId)
    val match = gpt5Regex.matchEntire(normalizedModelId) ?: return null
    val minorVersion = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
    val suffix = match.groupValues[2]

    return when (minorVersion) {
        null -> matchGpt5Bucket(suffix)
        1 -> matchGpt51Bucket(suffix)
        2 -> matchGpt52Bucket(suffix)
        3 -> matchGpt53Bucket(suffix)
        4 -> matchGpt54Bucket(suffix)
        else -> null
    }
}

private fun normalizeModelId(modelId: String): String {
    return modelId
        .trim()
        .lowercase()
        .substringAfterLast('/')
        .substringBefore('?')
}

private fun clampReasoningLevel(
    requestedLevel: ReasoningLevel,
    supportedLevels: List<ReasoningLevel>
): ReasoningLevel {
    val requestedPriority = priorityOf(requestedLevel)
    val supportedByPriority = supportedLevels.sortedBy { priorityOf(it) }
    return supportedByPriority.firstOrNull { priorityOf(it) >= requestedPriority } ?: supportedByPriority.last()
}

private fun priorityOf(level: ReasoningLevel): Int {
    return reasoningPriorityByLevel[level] ?: Int.MAX_VALUE
}

private fun matchGpt5Bucket(suffix: String): GptReasoningBucket? {
    return when {
        matchesBaseOrSnapshot(suffix) -> GptReasoningBucket.GPT_5
        matchesAliasOrSnapshot(suffix, "mini") -> GptReasoningBucket.GPT_5
        matchesAliasOrSnapshot(suffix, "nano") -> GptReasoningBucket.GPT_5
        matchesAliasOrSnapshot(suffix, "pro") -> GptReasoningBucket.GPT_5_PRO
        matchesAliasOrSnapshot(suffix, "codex") -> GptReasoningBucket.GPT_5_CODEX
        else -> null
    }
}

private fun matchGpt51Bucket(suffix: String): GptReasoningBucket? {
    return when {
        matchesBaseOrSnapshot(suffix) -> GptReasoningBucket.GPT_5_1
        matchesAliasOrSnapshot(suffix, "codex-max") -> GptReasoningBucket.GPT_5_1_CODEX_MAX
        matchesAliasOrSnapshot(suffix, "codex") -> GptReasoningBucket.GPT_5_1_CODEX
        matchesAliasOrSnapshot(suffix, "codex-mini") -> GptReasoningBucket.GPT_5_1_CODEX
        else -> null
    }
}

private fun matchGpt52Bucket(suffix: String): GptReasoningBucket? {
    return when {
        matchesBaseOrSnapshot(suffix) -> GptReasoningBucket.GPT_5_2_PLUS_BASE
        matchesAliasOrSnapshot(suffix, "pro") -> GptReasoningBucket.GPT_5_2_PLUS_PRO
        matchesAliasOrSnapshot(suffix, "codex") -> GptReasoningBucket.GPT_5_2_PLUS_CODEX
        else -> null
    }
}

private fun matchGpt53Bucket(suffix: String): GptReasoningBucket? {
    return when {
        matchesAliasOrSnapshot(suffix, "codex") -> GptReasoningBucket.GPT_5_2_PLUS_CODEX
        else -> null
    }
}

private fun matchGpt54Bucket(suffix: String): GptReasoningBucket? {
    return when {
        matchesBaseOrSnapshot(suffix) -> GptReasoningBucket.GPT_5_2_PLUS_BASE
        matchesAliasOrSnapshot(suffix, "mini") -> GptReasoningBucket.GPT_5_2_PLUS_BASE
        matchesAliasOrSnapshot(suffix, "nano") -> GptReasoningBucket.GPT_5_2_PLUS_BASE
        matchesAliasOrSnapshot(suffix, "pro") -> GptReasoningBucket.GPT_5_2_PLUS_PRO
        else -> null
    }
}

private fun matchesBaseOrSnapshot(suffix: String): Boolean {
    return suffix.isEmpty() || snapshotSuffixRegex.matches(suffix)
}

private fun matchesAliasOrSnapshot(suffix: String, alias: String): Boolean {
    if (suffix == alias) return true
    if (!suffix.startsWith("$alias-")) return false

    return snapshotSuffixRegex.matches(suffix.removePrefix("$alias-"))
}
