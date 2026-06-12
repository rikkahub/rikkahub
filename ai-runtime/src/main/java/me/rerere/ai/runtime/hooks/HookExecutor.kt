package me.rerere.ai.runtime.hooks

/**
 * Executor port for hook handlers (DIP — spec §HookDispatcher). The dispatcher depends only on
 * this shape; concrete executors (the app's LLM executor in v1) are bound per handler type at the
 * composition root, so adding a v2 handler type is additive — zero edits to the dispatcher or the
 * agent loop.
 */
interface HookExecutor {
    /**
     * Runs [handler] against [input] (the dispatched event payload, a JSON string) and returns the
     * handler's raw output text for [parseHookOutput]. [event] tells the producer which event
     * shape to emit — parse-time validation rejects an output claiming a different event.
     *
     * May throw and may suspend indefinitely: the dispatcher owns the timeout and maps any
     * failure through the handler's `failClosed` policy. Implementations must remain
     * cancellation-transparent (no swallowing of CancellationException).
     */
    suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String
}
