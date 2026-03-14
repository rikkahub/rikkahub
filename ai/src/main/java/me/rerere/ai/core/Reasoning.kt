package me.rerere.ai.core

enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    OFF(0, "none"),
    AUTO(-1, "auto"),
    LOW(1024, "low"),
    MEDIUM(16_000, "medium"),
    HIGH(32_000, "high"),
    XHIGH(64_000, "xhigh");

    val isEnabled: Boolean
        get() = this != OFF

    fun toOpenAIChatCompletionsEffort(): String {
        // Chat Completions 最高只到 high 所以 xhigh 降级为 high
        return when (this) {
            OFF, AUTO, LOW -> "low"
            MEDIUM -> "medium"
            HIGH, XHIGH -> "high"
        }
    }

    companion object {
        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull { kotlin.math.abs(it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)) } ?: AUTO
        }
    }
}
