package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.rikkahub.data.telegram.TelegramPollStallTracker
import kotlin.coroutines.coroutineContext

private const val TAG = "TelegramPollStall"

/**
 * Phase 24 — periodic stall checker for the Telegram long-poll loop.
 *
 * Launched as a child coroutine of [TelegramBotService]'s scope (so it is auto-cancelled
 * on FGS teardown). Every [CHECK_INTERVAL_MS] it asks the [TelegramPollStallTracker]
 * whether `getUpdates` has gone silent. On a stall it recycles the POLL LOOP — not the
 * service: the FGS stays alive and only the long-poll coroutine is torn down and
 * relaunched, which avoids the FGS-restart cost (notification flash, OkHttp re-warm) when
 * the stall is just a stuck connection.
 *
 * Flapping defence: if the loop has been recycled more than
 * [TelegramPollStallTracker.FLAP_RESTART_CEILING] times within
 * [TelegramPollStallTracker.FLAP_WINDOW_MS], the checker escalates to a full FGS restart
 * (via [onEscalate]) instead of another local recycle — a poll loop that keeps stalling
 * usually means a network or token problem the local recycle can't fix.
 *
 * The decision logic is factored into [decide] so it can be unit-tested without a live
 * coroutine or a real service.
 */
class TelegramPollStallChecker(
    private val tracker: TelegramPollStallTracker,
    /** Tears down the current poll coroutine and starts a fresh one. */
    private val restartPollLoop: () -> Unit,
    /** Escalation hook — full FGS restart when the loop is flapping. */
    private val onEscalate: () -> Unit,
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS,
    private val stallThresholdMs: Long = TelegramPollStallTracker.DEFAULT_STALL_THRESHOLD_MS,
) {

    /** What the checker should do on a given tick. */
    enum class Action {
        /** Poll loop is healthy — nothing to do. */
        NONE,

        /** Stalled, restart count under the flap ceiling — recycle the poll loop. */
        RESTART_POLL_LOOP,

        /** Stalled AND flapping — escalate to a full FGS restart. */
        ESCALATE,
    }

    /**
     * Pure decision: given whether the loop is stalled and how many times it has already
     * been restarted within the flap window, decide what to do. No side effects.
     */
    fun decide(stalled: Boolean, restartCountInWindow: Int): Action = when {
        !stalled -> Action.NONE
        restartCountInWindow >= TelegramPollStallTracker.FLAP_RESTART_CEILING -> Action.ESCALATE
        else -> Action.RESTART_POLL_LOOP
    }

    /**
     * The monitor loop. Runs until its coroutine is cancelled (FGS teardown). Tracks the
     * restart count inside a sliding [TelegramPollStallTracker.FLAP_WINDOW_MS] window so a
     * slow trickle of restarts over hours doesn't falsely trip the flap escalation.
     */
    suspend fun monitor() {
        logSafe { Log.i(TAG, "monitor: starting (interval=${checkIntervalMs}ms threshold=${stallThresholdMs}ms)") }
        var windowStartMs = System.currentTimeMillis()
        var restartsInWindow = 0
        while (coroutineContext.isActive) {
            delay(checkIntervalMs)
            val nowMs = System.currentTimeMillis()
            // Slide the flap window forward; reset the in-window counter when it expires.
            if (nowMs - windowStartMs > TelegramPollStallTracker.FLAP_WINDOW_MS) {
                windowStartMs = nowMs
                restartsInWindow = 0
            }
            val stalled = tracker.isStalled(stallThresholdMs)
            when (decide(stalled, restartsInWindow)) {
                Action.NONE -> Unit
                Action.RESTART_POLL_LOOP -> {
                    logSafe {
                        Log.w(
                            TAG,
                            "monitor: poll stalled for ${tracker.millisSinceLastUpdate()}ms — restarting poll loop"
                        )
                    }
                    tracker.onPollLoopRestarted()
                    restartsInWindow++
                    runCatching { restartPollLoop() }
                        .onFailure { logSafe { Log.w(TAG, "monitor: restartPollLoop failed", it) } }
                    // Reset the baseline so the next tick doesn't immediately re-fire on the
                    // same stall while the fresh poll loop is warming up.
                    tracker.markUpdate()
                }
                Action.ESCALATE -> {
                    logSafe {
                        Log.w(
                            TAG,
                            "monitor: poll loop flapping (${restartsInWindow} restarts in window) — escalating to FGS restart"
                        )
                    }
                    runCatching { onEscalate() }
                        .onFailure { logSafe { Log.w(TAG, "monitor: onEscalate failed", it) } }
                    // Escalation hands off to TelegramBotHealthWorker / a service restart;
                    // reset the window so we don't escalate again on the very next tick.
                    windowStartMs = nowMs
                    restartsInWindow = 0
                    tracker.markUpdate()
                }
            }
        }
        logSafe { Log.i(TAG, "monitor: stopped") }
    }

    companion object {
        /** 2-minute cadence — see class kdoc. */
        const val CHECK_INTERVAL_MS = 2L * 60_000L
    }
}

/**
 * Run a [android.util.Log] call inside a guard so JVM unit tests — where `android.util.Log`
 * is an unmocked stub that throws — don't crash the monitor loop under test.
 */
private inline fun logSafe(block: () -> Unit) {
    runCatching { block() }
}
