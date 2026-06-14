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

/**
 * The decision a deterministic [HookHandler.Static] gate emits. ASK is the conservative usable
 * default (HITL: the user confirms each matching call); DENY blocks the call outright. There is no
 * ALLOW mode — a static handler that allows is just an absent matcher, so it would be dead weight.
 */
@Serializable
enum class GuardrailMode { ASK, DENY }

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

    /**
     * A deterministic gate that emits a fixed [GuardrailMode] decision with no provider round-trip —
     * the managed PreToolUse guardrail over high-risk device/automation tools (#187 v2 / M5). It is
     * fail-closed by default: a guardrail whose executor somehow errors must still deny rather than
     * silently let the gated tool through. Additive sealed leaf, so adding it is zero edits to the
     * dispatcher or the agent loop (DIP) — only a composition-root executor binding.
     */
    @Serializable
    @SerialName("static")
    data class Static(
        val mode: GuardrailMode = GuardrailMode.ASK,
        val reason: String = "",
        override val failClosed: Boolean = true,
    ) : HookHandler()

    // Js / Subagent / Http are v2 — intentionally absent so v1 rejects them at decode time
    // (the pinned QuickJS wrapper has no interrupt API; running imported JS without a
    // preemptive timeout was the design-gate reject reason).
}
