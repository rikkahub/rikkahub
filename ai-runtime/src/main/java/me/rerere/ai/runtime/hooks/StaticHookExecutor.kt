package me.rerere.ai.runtime.hooks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Executor for the deterministic [HookHandler.Static] guardrail (#187 v2 / M5). It does NOT call a
 * provider: a static gate's decision is fixed by its [HookHandler.Static.mode], so it emits the canned
 * decision wire the dispatcher's [parseHookOutput] already consumes — the same shape an llm handler
 * would return, just without the round-trip. This keeps the gate free (no token spend) and instant.
 *
 * Pure JVM (no Android, no network), so the managed-guardrail path is unit-testable exactly like the
 * rest of the hook kernel. Bound per handler type at the composition root (DIP); the dispatcher and
 * the agent loop never reference it.
 */
class StaticHookExecutor(
    private val json: Json = Json,
) : HookExecutor {

    override suspend fun execute(event: HookEvent, handler: HookHandler, input: String): String {
        check(handler is HookHandler.Static) {
            "StaticHookExecutor cannot run ${handler::class.simpleName} handlers"
        }
        // The wire decision is the lowercase token parseHookOutput expects ("ask" | "deny"). The
        // hookEventName is echoed so the parser's event-spoof guard passes for the dispatched event.
        val decision = when (handler.mode) {
            GuardrailMode.ASK -> "ask"
            GuardrailMode.DENY -> "deny"
        }
        return json.encodeToString(
            buildJsonObject {
                put("hookEventName", event.name)
                put("decision", decision)
                if (handler.reason.isNotBlank()) put("reason", handler.reason)
            }
        )
    }
}
