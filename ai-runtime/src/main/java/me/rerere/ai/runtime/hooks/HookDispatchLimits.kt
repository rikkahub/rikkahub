package me.rerere.ai.runtime.hooks

/**
 * Runtime budget controls for hook dispatch fanout.
 */
data class HookDispatchLimits(
    val maxHandlersPerDispatch: Int = 8,
    val maxHandlerExecutionsPerGeneration: Int = 32,
)

/**
 * Tracks remaining per-generation hook handler execution budget for a single turn.
 */
class HookWorkBudget(private val limits: HookDispatchLimits) {
    private var remainingExecutions = limits.maxHandlerExecutionsPerGeneration

    val remaining: Int
        get() = remainingExecutions

    fun tryConsume(): Boolean {
        if (remainingExecutions <= 0) return false
        remainingExecutions--
        return true
    }
}
