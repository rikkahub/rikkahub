package me.rerere.locallm.litert

import me.rerere.ai.core.Tool
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe per-request snapshot of the tools the [LiteRtToolBridge] is allowed to
 * dispatch. The provider populates this before invoking the SDK and clears it on stream
 * completion. Reads are lock-free (ConcurrentHashMap), which matters because the SDK
 * invokes @Tool methods on its internal engine thread, not the caller's coroutine.
 *
 * Lifetime: one entry per concurrent inference. The single-bridge approach assumes the
 * runtime serialises inferences via [LiteRtRuntime.mutex] — only one request is in flight
 * at a time, so a process-singleton snapshot is sufficient.
 */
object LiteRtToolBridgeRegistry {

    private val tools = ConcurrentHashMap<String, Tool>()

    /** Replace the current snapshot with [newTools]. Idempotent. */
    fun setForRequest(newTools: List<Tool>) {
        tools.clear()
        for (tool in newTools) {
            tools[tool.name] = tool
        }
    }

    /** Look up a tool by its registered name. Returns null when no tool matches. */
    fun lookup(name: String): Tool? = tools[name]

    /** Snapshot of every tool name registered for the current request. */
    fun currentToolNames(): Set<String> = tools.keys.toSet()

    /** Empty the registry. Call from a `finally` after every inference. */
    fun clear() {
        tools.clear()
    }
}
