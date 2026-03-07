package me.rerere.ai.core

enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    OFF(0, "none"),
    AUTO(-1, "auto"),
    LOW(1024, "low"),
    MEDIUM(16_000, "medium"),
    HIGH(32_000, "high");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull { kotlin.math.abs(it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)) } ?: AUTO
        }
    }
}

private fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun resolveOpenAIChatCompletionsReasoningEffort(
    thinkingBudget: Int?,
    overrideEffort: String,
): String? {
    overrideEffort.trimToNull()?.let { return it }
    val level = ReasoningLevel.fromBudgetTokens(thinkingBudget)
    if (level == ReasoningLevel.AUTO) return null
    return if (level == ReasoningLevel.OFF) "low" else level.effort
}

fun resolveOpenAIResponsesReasoningEffort(
    thinkingBudget: Int?,
    overrideEffort: String,
): String? {
    overrideEffort.trimToNull()?.let { return it }
    val level = ReasoningLevel.fromBudgetTokens(thinkingBudget ?: 0)
    return if (level == ReasoningLevel.AUTO) null else level.effort
}
