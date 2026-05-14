package me.rerere.rikkahub.data.telegram

import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 24 — per-update timestamp + restart counter for Telegram long-poll stall detection.
 *
 * `TelegramBotHealthWorker` only knows whether the foreground service is alive — it can't
 * see a `getUpdates` loop that's gone silent INSIDE a live FGS (stuck socket, OEM network
 * freeze, DNS wedge after a background transition). This tracker closes that gap: the poll
 * loop calls [markUpdate] on every successful `getUpdates` (success OR empty), and a
 * companion checker periodically asks [isStalled].
 *
 * One instance per [me.rerere.rikkahub.service.TelegramBotService] lifecycle. Thread-safe:
 * the poll loop and the checker run on different coroutines.
 */
class TelegramPollStallTracker {

    private val lastUpdateAtMs = AtomicLong(System.currentTimeMillis())

    /**
     * Number of times the stall checker has recycled the poll loop. Telemetry only — read
     * by the checker's flapping-defence logic and the Doctor `service.telegram_poll_stall`
     * row.
     */
    @Volatile
    var restartCount: Int = 0
        private set

    /** Called on every successful `getUpdates` round-trip — success or empty result. */
    fun markUpdate() {
        lastUpdateAtMs.set(System.currentTimeMillis())
    }

    /** Milliseconds since the last successful `getUpdates`. */
    fun millisSinceLastUpdate(): Long = System.currentTimeMillis() - lastUpdateAtMs.get()

    /**
     * True when the poll loop has been silent longer than [thresholdMs].
     *
     * Default 90 s = three missed 30-second long-poll cycles — unambiguous silence rather
     * than a slow network. `getUpdates` long-polls with a `timeout=30` parameter so the
     * steady-state cadence is at most ~30 s.
     */
    fun isStalled(thresholdMs: Long = DEFAULT_STALL_THRESHOLD_MS): Boolean =
        millisSinceLastUpdate() > thresholdMs

    /** Incremented by the checker when it recycles the poll loop. */
    fun onPollLoopRestarted() {
        restartCount++
    }

    companion object {
        /** 90 seconds — see [isStalled] kdoc for the justification. */
        const val DEFAULT_STALL_THRESHOLD_MS = 90_000L

        /** Restart-count ceiling within [FLAP_WINDOW_MS] before escalating to a FGS restart. */
        const val FLAP_RESTART_CEILING = 5

        /** Window over which [FLAP_RESTART_CEILING] restarts counts as flapping. */
        const val FLAP_WINDOW_MS = 30L * 60_000L
    }
}
