package me.rerere.ai.util

import java.io.IOException
import kotlin.random.Random

/**
 * Pure, provider-agnostic policy for retrying a streaming (SSE) connection that fails
 * BEFORE the first stream frame is received.
 *
 * WHY this exists: a connection to a Responses/streaming endpoint can die between the
 * HTTP open and the first SSE event (DNS hiccup, connection reset, proxy 502, a 429 the
 * server emits before streaming). Retrying that narrow transient window is safe because
 * no content has been delivered yet, so there is nothing to duplicate. Once the first
 * frame lands, retrying could replay partial content — so callers must stop retrying then.
 *
 * Bound and shape mirror the OpenAI SDK (max_retries = 2, exponential backoff with a cap)
 * and Koog's RetryingLLMClient. The gate is transient-only: a non-IOException throwable or
 * a non-transient HTTP status is NOT retried, so this never degrades into a blanket catch.
 */

/** Initial open + up to this many retries (3 opens max), matching OpenAI SDK max_retries. */
const val STREAM_MAX_RETRIES = 2

private val RETRYABLE_HTTP_CODES = setOf(408, 409, 429, 500, 502, 503, 504, 529)

/**
 * True iff the failure is a transient pre-first-frame condition worth retrying:
 * - [throwable] is an [IOException] (covers SocketTimeoutException, okhttp StreamResetException,
 *   and generic connection/timeout failures), OR
 * - [httpCode] is one of the transient server/rate-limit statuses.
 *
 * A non-IOException throwable (e.g. a parse/state error) and a non-transient status
 * (400/401/404, …) return false — the gate stays narrow on purpose.
 */
fun isRetryableStreamFailure(throwable: Throwable?, httpCode: Int?): Boolean {
    if (throwable is IOException) return true
    return httpCode != null && httpCode in RETRYABLE_HTTP_CODES
}

/**
 * Deterministic, jitter-free backoff base in milliseconds.
 *
 * If the server told us how long to wait via [retryAfterMs] and it is within a sane bound
 * (1..60_000 ms), honor it verbatim. Otherwise use exponential backoff 0.5s * 2^attempt,
 * clamped at 8s. Jitter is intentionally kept OUT of this function so it stays unit-testable;
 * [jitteredBackoffMillis] applies jitter on top for production use.
 */
fun retryBackoffMillis(attempt: Int, retryAfterMs: Long?): Long {
    if (retryAfterMs != null && retryAfterMs in 1L..60_000L) return retryAfterMs
    return (500L shl attempt).coerceAtMost(8_000L)
}

/**
 * Production backoff: the clamped base with up to 25% downward jitter to avoid thundering-herd
 * reconnect storms. An honored Retry-After value is returned unjittered (the server's number
 * is authoritative). [random] is injectable for deterministic testing.
 */
fun jitteredBackoffMillis(
    attempt: Int,
    retryAfterMs: Long?,
    random: Random = Random.Default
): Long {
    if (retryAfterMs != null && retryAfterMs in 1L..60_000L) return retryAfterMs
    val base = retryBackoffMillis(attempt, null)
    return (base * (1.0 - 0.25 * random.nextDouble())).toLong()
}

/**
 * Parse a server-supplied retry delay into milliseconds.
 * Prefers the `retry-after-ms` header (already in ms), else `Retry-After` (seconds).
 * Returns null when both are absent, unparseable, or <= 0.
 */
fun retryAfterMillisFromHeaders(retryAfterMs: String?, retryAfter: String?): Long? {
    retryAfterMs?.trim()?.toLongOrNull()?.let { if (it > 0) return it }
    retryAfter?.trim()?.toLongOrNull()?.let { if (it > 0) return it * 1000L }
    return null
}
