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

private val gpt5Regex = Regex("^gpt-5(?:\\.(\\d+))?(?:-([a-z0-9.-]+))?$")

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

fun Model.supportsReasoningConfiguration(): Boolean {
    return abilities.contains(ModelAbility.REASONING) || isGptReasoningModel(modelId)
}

private fun getGptReasoningBucket(modelId: String): GptReasoningBucket? {
    val normalizedModelId = normalizeModelId(modelId)
    val match = gpt5Regex.matchEntire(normalizedModelId) ?: return null
    val minorVersion = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
    val suffix = match.groupValues[2]

    return when {
        minorVersion == null && suffix.startsWith("codex") -> GptReasoningBucket.GPT_5_CODEX
        minorVersion == null && suffix.startsWith("pro") -> GptReasoningBucket.GPT_5_PRO
        minorVersion == null && suffix.isEmpty() -> GptReasoningBucket.GPT_5
        minorVersion == 1 && suffix.startsWith("codex-max") -> GptReasoningBucket.GPT_5_1_CODEX_MAX
        minorVersion == 1 && suffix.startsWith("codex") -> GptReasoningBucket.GPT_5_1_CODEX
        minorVersion == 1 -> GptReasoningBucket.GPT_5_1
        minorVersion != null && minorVersion >= 2 && suffix.startsWith("pro") -> GptReasoningBucket.GPT_5_2_PLUS_PRO
        minorVersion != null && minorVersion >= 2 && suffix.contains("codex") -> GptReasoningBucket.GPT_5_2_PLUS_CODEX
        minorVersion != null && minorVersion >= 2 -> GptReasoningBucket.GPT_5_2_PLUS_BASE
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
    val requestedPriority = reasoningPriority.indexOf(requestedLevel)
    val supportedByPriority = supportedLevels.sortedBy { reasoningPriority.indexOf(it) }
    return supportedByPriority.firstOrNull { reasoningPriority.indexOf(it) >= requestedPriority }
        ?: supportedByPriority.last()
}
