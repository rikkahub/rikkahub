package me.rerere.ai.core

import kotlin.math.ceil

// Default reply reservation (tokens) when the assistant declares no maxTokens. Room for the model's
// own output so a full window doesn't leave zero space to answer.
const val DEFAULT_MAX_OUTPUT = 8_192

// Upper bound on the reply reservation. A model configured with a huge maxTokens (e.g. 64k) would
// otherwise over-reserve and compact far too early; cap it. (claude-code MAX_OUTPUT_TOKENS_FOR_SUMMARY
// = 20_000, grounded in observed p99.99 summary output.)
const val MAX_OUTPUT_RESERVE_CAP = 20_000

// Absolute headroom against estimation drift and prompt-cache jitter. An absolute margin is more
// honest than a percentage, which silently shrinks on small-window models and bloats on large ones.
// (claude-code AUTOCOMPACT_BUFFER_TOKENS = 13_000.)
const val SAFETY_BUFFER_TOKENS = 13_000

// Lower bound the hard guard clamps to when reserveOutput + safetyBuffer would meet or exceed the
// window (tiny window + big maxTokens). Keeps allowedTokens strictly in (0, window).
const val ALLOWED_TOKENS_FLOOR = 1_000

// Soft (quality) threshold is a fraction of the window, clamped to a sane band. The lower bound keeps
// a misconfigured 0 from compacting on every turn; the upper bound keeps it from never firing.
const val MIN_THRESHOLD_FRACTION = 0.05f
const val MAX_THRESHOLD_FRACTION = 1.0f

/**
 * One canonical token-pressure reading (design #193 R3/R4), consumed by BOTH the auto-compact trigger
 * and the size warning so they cannot disagree on whether the conversation is over threshold. Pure.
 *
 * @property contextTokens current footprint from [contextTokens] (real anchor + pending estimate).
 * @property window resolved model context window (always > 0; from getContextWindowForModel).
 * @property allowedTokens hard ceiling = window - reserveOutput - safetyBuffer, clamped to
 *   (0, window). The reply reservation and the safety buffer are decomposed (R4) rather than folded
 *   into one percentage.
 * @property usedFraction contextTokens / window (non-decreasing in contextTokens).
 * @property softOver soft quality threshold crossed (`contextTokens >= ceil(window * threshold)`).
 * @property hardOver hard safety guard crossed (`contextTokens > allowedTokens`). Dominates softOver.
 */
data class TokenPressure(
    val contextTokens: Int,
    val window: Int,
    val allowedTokens: Int,
    val usedFraction: Float,
    val softOver: Boolean,
    val hardOver: Boolean,
)

/**
 * Resolve the reply reservation from an assistant's configured max output tokens (R4 / refinement #2):
 * use it when positive, else [DEFAULT_MAX_OUTPUT], then cap at [MAX_OUTPUT_RESERVE_CAP].
 */
fun resolveReserveOutput(maxTokens: Int?): Int =
    (maxTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT).coerceAtMost(MAX_OUTPUT_RESERVE_CAP)

/**
 * Hard ceiling on usable prompt tokens for a window: `window - reserveOutput - safetyBuffer`, clamped
 * into `(0, window)` so the guard is always meaningful even on a tiny window with a large reserve.
 * Decreases as either reserve grows (P9). Always `0 < allowedTokens < window` for `window > 0`.
 */
fun computeAllowedTokens(
    window: Int,
    reserveOutput: Int,
    safetyBuffer: Int = SAFETY_BUFFER_TOKENS,
): Int {
    require(window >= 2) { "window must be >= 2 to admit a usable allowance, was $window" }
    val hi = window - 1                                   // strictly below the window
    val lo = ALLOWED_TOKENS_FLOOR.coerceIn(1, hi)         // never <= 0, never above hi
    val raw = window - reserveOutput - safetyBuffer
    // Clamp into [lo, hi] -> always 0 < allowedTokens < window.
    return raw.coerceIn(lo, hi)
}

/**
 * Compute the canonical [TokenPressure] for a turn. [thresholdFraction] is the soft quality knob
 * (e.g. assistant.autoCompactThreshold), clamped to [[MIN_THRESHOLD_FRACTION], [MAX_THRESHOLD_FRACTION]].
 *
 * Requires `window > 0` (guaranteed by getContextWindowForModel).
 */
fun tokenPressure(
    contextTokens: Int,
    window: Int,
    thresholdFraction: Float,
    reserveOutput: Int,
    safetyBuffer: Int = SAFETY_BUFFER_TOKENS,
): TokenPressure {
    require(window >= 2) { "window must be >= 2, was $window" }
    val ctx = contextTokens.coerceAtLeast(0)
    val allowed = computeAllowedTokens(window, reserveOutput, safetyBuffer)
    val fraction = thresholdFraction.coerceIn(MIN_THRESHOLD_FRACTION, MAX_THRESHOLD_FRACTION)
    val softLimit = ceil(window.toDouble() * fraction).toInt()
    return TokenPressure(
        contextTokens = ctx,
        window = window,
        allowedTokens = allowed,
        usedFraction = ctx.toFloat() / window.toFloat(),
        softOver = ctx >= softLimit,
        hardOver = ctx > allowed,
    )
}

/**
 * Pure auto-compact trigger (design #193, replacing the count-based predicate). Fires only when the
 * user opted in, there is compressible history, the per-session circuit breaker has not tripped, and
 * EITHER the soft quality threshold OR the hard safety guard is crossed. The hard guard dominates: a
 * high soft percent can never mask it (P8 / T-dominance).
 *
 * @param enabled user explicitly enabled auto-compact (it rewrites history; never silent).
 * @param hasCompressibleHistory there is actually history to compress (else compressConversation
 *   would throw "not enough messages"); also acts as the cold-start guard.
 * @param breakerTripped per-session breaker is open (an irrecoverably-over-limit conversation must
 *   not retry a doomed compaction every turn).
 * @param pressure the canonical [TokenPressure] for this turn.
 */
fun shouldAutoCompact(
    enabled: Boolean,
    hasCompressibleHistory: Boolean,
    breakerTripped: Boolean,
    pressure: TokenPressure,
): Boolean {
    if (!enabled) return false
    if (!hasCompressibleHistory) return false
    if (breakerTripped) return false
    if (pressure.contextTokens <= 0) return false
    return pressure.softOver || pressure.hardOver
}
