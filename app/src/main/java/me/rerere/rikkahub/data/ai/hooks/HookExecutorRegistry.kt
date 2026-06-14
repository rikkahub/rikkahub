package me.rerere.rikkahub.data.ai.hooks

import me.rerere.ai.runtime.hooks.HookExecutor
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.StaticHookExecutor
import kotlin.reflect.KClass

/**
 * Composition-root binding of handler types to executors (#200 v1, spec §HookDispatcher DIP).
 * The dispatcher consumes only the [executors] map; adding a handler type (the M5 static guardrail
 * here, or a v2 js/subagent/http handler) is an additive constructor parameter + map entry — zero
 * edits to the dispatcher or the agent loop.
 */
class HookExecutorRegistry(
    llm: LlmHookExecutor,
    static: StaticHookExecutor,
) {
    val executors: Map<KClass<out HookHandler>, HookExecutor> = mapOf(
        HookHandler.Llm::class to llm,
        HookHandler.Static::class to static,
    )
}
