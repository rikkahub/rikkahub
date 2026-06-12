package me.rerere.ai.runtime.hooks

import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.runtime.contract.RuntimeLogSink
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "HookDispatcher"

/** Per-dispatch context: the Assistant's hook config plus the tool name the matcher filters on. */
data class HookDispatchContext(
    val config: HookConfig,
    val toolName: String? = null,
)

/**
 * Dispatches one lifecycle event through the Assistant's configured hooks (spec §HookDispatcher):
 * trust gate -> matcher filter -> injected [HookExecutor] port -> per-hook timeout ->
 * [parseHookOutput] -> [aggregate]. The agent loop consumes only the [AggregatedHookResult]; it
 * never sees individual handlers, so v2 handler types bind at the composition root without
 * touching the loop (DIP).
 */
class HookDispatcher(
    private val executors: Map<KClass<out HookHandler>, HookExecutor>,
    private val logSink: RuntimeLogSink,
    private val perHookTimeout: Duration = 30.seconds,
) {
    suspend fun dispatch(event: HookEvent, input: String, ctx: HookDispatchContext): AggregatedHookResult {
        // Import-trust gate (H4): untrusted hooks must never run — passthrough, no executor call.
        if (!ctx.config.trusted) return AggregatedHookResult()
        val outputs = ctx.config.hooks[event].orEmpty()
            .filter { matchesIf(it, ctx.toolName) }
            .flatMap { matcher -> matcher.handlers.map { runHandler(event, it, input) } }
        return aggregate(outputs)
    }

    private suspend fun runHandler(event: HookEvent, handler: HookHandler, input: String): HookOutput {
        val executor = executors[handler::class]
            ?: return failureOutput(handler, "no executor bound for ${handler::class.simpleName}")
        val raw = try {
            withTimeoutOrNull(perHookTimeout) { executor.execute(event, handler, input) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failureOutput(handler, "executor error: ${e.message}", e)
        } ?: return failureOutput(handler, "hook timed out after $perHookTimeout")
        return when (val parsed = parseHookOutput(raw, event)) {
            is HookOutputParseResult.Parsed -> parsed.output
            is HookOutputParseResult.Failure -> failureOutput(handler, parsed.detail)
        }
    }

    // failClosed handlers are security gates: any failure (error, timeout, unparseable or
    // spoofed output) must aggregate as Deny. Fail-open handlers degrade to a logged Allow —
    // logged precisely because the failure is otherwise invisible to the loop.
    private fun failureOutput(handler: HookHandler, detail: String, cause: Throwable? = null): HookOutput =
        if (handler.failClosed) {
            logSink.warn(TAG, "failClosed hook failed, denying: $detail", cause)
            HookOutput(decision = HookDecision.Deny("hook failed (fail-closed): $detail"))
        } else {
            logSink.warn(TAG, "hook failed, allowing (fail-open): $detail", cause)
            HookOutput()
        }
}
