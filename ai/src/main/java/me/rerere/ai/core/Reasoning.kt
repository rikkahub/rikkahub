package me.rerere.ai.core

enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    OFF(0, "none"),
    AUTO(-1, "auto"),
    MINIMAL(1, "minimal"),
    LOW(1024, "low"),
    MEDIUM(16_000, "medium"),
    HIGH(32_000, "high"),
    XHIGH(64_000, "xhigh");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        val defaultPresets = listOf(OFF, AUTO, LOW, MEDIUM, HIGH)

        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            if (budgetTokens == null) return AUTO

            entries.firstOrNull { it.budgetTokens == budgetTokens }?.let { return it }

            return defaultPresets.minByOrNull { kotlin.math.abs(it.budgetTokens - budgetTokens) } ?: AUTO
        }
    }
}
