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
        val compatibilityPresets = listOf(OFF, AUTO, LOW, MEDIUM, HIGH)

        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            if (budgetTokens == null) return AUTO

            entries.firstOrNull { it.budgetTokens == budgetTokens }?.let { return it }

            return compatibilityPresets.minByOrNull { kotlin.math.abs(it.budgetTokens - budgetTokens) } ?: AUTO
        }

        fun fromCompatibilityBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            if (budgetTokens == null) return AUTO

            if (budgetTokens == MINIMAL.budgetTokens) return LOW
            if (budgetTokens == XHIGH.budgetTokens) return HIGH

            compatibilityPresets.firstOrNull { it.budgetTokens == budgetTokens }?.let { return it }

            return compatibilityPresets.minByOrNull { kotlin.math.abs(it.budgetTokens - budgetTokens) } ?: AUTO
        }
    }
}
