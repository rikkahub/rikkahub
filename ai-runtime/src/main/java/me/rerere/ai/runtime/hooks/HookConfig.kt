package me.rerere.ai.runtime.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * User-configurable event hooks (#200 v1). Persisted as JSON inside the Assistant record, so every
 * field is additive with a default — old JSON without these keys must decode unchanged.
 */
@Serializable
enum class HookEvent {
    PreToolUse,
    UserPromptSubmit,
    Stop,
}

@Serializable
data class HookConfig(
    val hooks: Map<HookEvent, List<HookMatcher>> = emptyMap(),
    // Import-trust gate (H4): imported/restored hooks must never run before the user reviews
    // them, so an absent (or attacker-omitted) field decodes to the fail-closed value.
    val trusted: Boolean = false,
)

@Serializable
data class HookMatcher(
    val matcher: String? = null, // null = always matches
    val handlers: List<HookHandler> = emptyList(),
)

@Serializable
sealed class HookHandler {
    // Security hooks: an executor error/timeout on a failClosed handler aggregates as Deny.
    abstract val failClosed: Boolean

    @Serializable
    @SerialName("llm")
    data class Llm(
        val prompt: String,
        val model: Uuid? = null, // null = the settings fast model
        override val failClosed: Boolean = false,
    ) : HookHandler()

    // Js / Subagent / Http are v2 — intentionally absent so v1 rejects them at decode time
    // (the pinned QuickJS wrapper has no interrupt API; running imported JS without a
    // preemptive timeout was the design-gate reject reason).
}
