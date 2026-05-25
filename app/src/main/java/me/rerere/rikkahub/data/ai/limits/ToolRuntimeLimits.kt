package me.rerere.rikkahub.data.ai.limits

import me.rerere.rikkahub.data.preferences.TermuxDefaults

/**
 * App-wide @Volatile runtime holder for tool execution limits that span all tool families
 * (not just Termux). Currently holds the per-turn wall-clock budget that was previously
 * hardcoded in GenerationHandler.kt.
 *
 * Pushed from [me.rerere.rikkahub.data.preferences.TermuxPreferences.init] because the
 * Termux settings page is where the user configures it (per spec: "exposed on the Termux
 * page since that's where issue #5 raised it"). The field itself is app-wide and the
 * holder lives in data/ai/limits/ to reflect that.
 */
object ToolRuntimeLimits {
    @Volatile var turnBudgetMs: Long = TermuxDefaults.DEFAULT_TURN_BUDGET_MS
}
