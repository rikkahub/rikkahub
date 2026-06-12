package me.rerere.rikkahub.data.ai.hooks

import me.rerere.ai.runtime.hooks.HookExecutor
import me.rerere.ai.runtime.hooks.HookHandler
import kotlin.reflect.KClass

/**
 * Composition-root binding of handler types to executors (#200 v1, spec §HookDispatcher DIP).
 * The dispatcher consumes only the [executors] map; adding a v2 handler type (js/subagent/http)
 * is an additive constructor parameter + map entry here — zero edits to the dispatcher or the
 * agent loop.
 */
class HookExecutorRegistry(
    llm: LlmHookExecutor,
) {
    val executors: Map<KClass<out HookHandler>, HookExecutor> = mapOf(
        HookHandler.Llm::class to llm,
    )
}
