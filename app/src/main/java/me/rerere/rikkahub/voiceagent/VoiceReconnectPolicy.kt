package me.rerere.rikkahub.voiceagent

import android.os.SystemClock
import kotlin.math.roundToLong
import kotlin.random.Random

internal fun defaultReconnectClockMs(): Long =
    runCatching { SystemClock.elapsedRealtime() }
        .getOrElse { System.nanoTime() / 1_000_000L }

internal data class VoiceReconnectPolicy(
    val maxAttempts: Int = 15,
    val maxElapsedMs: Long = 30L * 60L * 1000L,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 5L * 60L * 1000L,
    val jitterRatio: Double = 0.2,
    val jitterSource: () -> Double = { Random.nextDouble(from = -1.0, until = 1.0) },
) {
    fun delayMsForAttempt(attempt: Int, elapsedMs: Long): Long? {
        if (attempt > maxAttempts) return null
        val remainingMs = maxElapsedMs - elapsedMs
        if (remainingMs <= 0L) return null
        val exponentialDelay = exponentialDelayMs(attempt)
        val jitterMs = (exponentialDelay * jitterRatio * jitterSource()).roundToLong()
        return (exponentialDelay + jitterMs)
            .coerceAtLeast(0L)
            .coerceAtMost(remainingMs)
    }

    private fun exponentialDelayMs(attempt: Int): Long {
        var delayMs = baseDelayMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            delayMs = (delayMs * 2L).coerceAtMost(maxDelayMs)
        }
        return delayMs.coerceAtMost(maxDelayMs)
    }
}
