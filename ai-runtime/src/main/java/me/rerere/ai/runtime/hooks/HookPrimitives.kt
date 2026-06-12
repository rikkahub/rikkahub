package me.rerere.ai.runtime.hooks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure decision primitives for the hook dispatcher (#200 v1). Everything here is
 * side-effect-free and order-deterministic so the agent loop's behavior is a pure function of
 * the handler outputs — the property suites in the test source set are the contract.
 */
sealed interface HookDecision {
    data object Allow : HookDecision
    data object Ask : HookDecision
    data class Deny(val reason: String) : HookDecision
}

/** The parsed output of a single hook handler invocation. */
data class HookOutput(
    val decision: HookDecision = HookDecision.Allow,
    val updatedInput: String? = null,
    val additionalContext: String? = null,
    val preventContinuation: Boolean = false,
)

/** The combined decision the agent loop consumes after all matched handlers ran. */
data class AggregatedHookResult(
    val decision: HookDecision = HookDecision.Allow,
    val updatedInput: String? = null,
    val additionalContext: String? = null,
    val preventContinuation: Boolean = false,
)

/**
 * Folds handler outputs (in handler order) into one result:
 * - decision: most restrictive wins — Deny > Ask > Allow; the FIRST Deny's reason is kept so
 *   the surfaced reason is stable regardless of what later handlers return.
 * - updatedInput: each rewrite supersedes the previous one; the last non-null rewrite wins.
 * - additionalContext: order-stable concatenation of every contributor.
 * - preventContinuation: logical OR.
 *
 * Empty input is the Allow passthrough.
 */
fun aggregate(outputs: List<HookOutput>): AggregatedHookResult {
    var decision: HookDecision = HookDecision.Allow
    var updatedInput: String? = null
    val contexts = mutableListOf<String>()
    var preventContinuation = false
    for (output in outputs) {
        decision = mostRestrictive(decision, output.decision)
        output.updatedInput?.let { updatedInput = it }
        output.additionalContext?.let { contexts += it }
        preventContinuation = preventContinuation || output.preventContinuation
    }
    return AggregatedHookResult(
        decision = decision,
        updatedInput = updatedInput,
        additionalContext = contexts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        preventContinuation = preventContinuation,
    )
}

private fun mostRestrictive(a: HookDecision, b: HookDecision): HookDecision = when {
    a is HookDecision.Deny -> a
    b is HookDecision.Deny -> b
    a == HookDecision.Ask || b == HookDecision.Ask -> HookDecision.Ask
    else -> HookDecision.Allow
}

/**
 * Matcher semantics (spec open Q #3): `null` always matches; otherwise exact tool-name match OR
 * full regex match. A non-null matcher against a null tool name (events that carry no tool, i.e.
 * UserPromptSubmit/Stop) is fail-closed: no match. An invalid regex pattern is not an error —
 * it simply degrades to the exact-name comparison (which already ran), because user-authored
 * matchers must never crash the agent loop.
 */
fun matches(matcher: String?, toolName: String?): Boolean {
    if (matcher == null) return true
    if (toolName == null) return false
    if (matcher == toolName) return true
    return runCatching { Regex(matcher).matches(toolName) }.getOrDefault(false)
}

/** [matches] lifted to a [HookMatcher]. */
fun matchesIf(matcher: HookMatcher, toolName: String?): Boolean =
    matches(matcher.matcher, toolName)

/**
 * Result of parsing a raw handler response. Hook output is untrusted text (an LLM wrote it),
 * so failures are values, never exceptions — the dispatcher maps a [Failure] through the
 * handler's `failClosed` flag instead of crashing the agent loop.
 */
sealed interface HookOutputParseResult {
    data class Parsed(val output: HookOutput) : HookOutputParseResult

    sealed interface Failure : HookOutputParseResult {
        val detail: String

        data class Malformed(override val detail: String) : Failure

        /**
         * The output named a different lifecycle event than the one actually dispatched.
         * Rejected outright: a handler must not smuggle, say, a Stop-shaped decision into a
         * PreToolUse gate (event spoofing).
         */
        data class EventMismatch(
            val claimedEvent: String,
            val dispatchedEvent: HookEvent,
        ) : Failure {
            override val detail: String
                get() = "hook output claims event '$claimedEvent' but '$dispatchedEvent' was dispatched"
        }
    }
}

/**
 * Parses one handler's raw response against the event that was actually dispatched.
 *
 * - Malformed JSON / wrong shape / unknown `decision` value → [HookOutputParseResult.Failure.Malformed].
 *   An unrecognized decision is a failure, not a silent Allow — whether that blocks is the
 *   handler's `failClosed` policy, decided by the dispatcher.
 * - `hookEventName`, when present, must equal the dispatched event exactly, otherwise
 *   [HookOutputParseResult.Failure.EventMismatch]. Absent = the output claims nothing.
 * - Absent `decision` is a context-only Allow (UserPromptSubmit/Stop hooks that merely inject).
 * - Unknown extra keys are ignored: the producer is an LLM and over-production is benign.
 */
fun parseHookOutput(raw: String, dispatchedEvent: HookEvent): HookOutputParseResult {
    val wire = try {
        HookOutputJson.decodeFromString<HookOutputWire>(raw)
    } catch (e: IllegalArgumentException) {
        // kotlinx throws SerializationException (an IllegalArgumentException) on bad JSON and
        // IllegalArgumentException on non-decodable input — both are "the text is not a hook
        // output", which is a value here, never a crash of the agent loop.
        return HookOutputParseResult.Failure.Malformed(e.message ?: "malformed hook output")
    }
    wire.hookEventName?.let { claimed ->
        if (claimed != dispatchedEvent.name) {
            return HookOutputParseResult.Failure.EventMismatch(claimed, dispatchedEvent)
        }
    }
    val decision = when (wire.decision) {
        null, "allow" -> HookDecision.Allow
        "ask" -> HookDecision.Ask
        "deny" -> HookDecision.Deny(wire.reason.orEmpty())
        else -> return HookOutputParseResult.Failure.Malformed("unknown decision '${wire.decision}'")
    }
    return HookOutputParseResult.Parsed(
        HookOutput(
            decision = decision,
            updatedInput = wire.updatedInput,
            additionalContext = wire.additionalContext,
            preventContinuation = wire.preventContinuation,
        ),
    )
}

private val HookOutputJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class HookOutputWire(
    val hookEventName: String? = null,
    val decision: String? = null,
    val reason: String? = null,
    val updatedInput: String? = null,
    val additionalContext: String? = null,
    val preventContinuation: Boolean = false,
)
