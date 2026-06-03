package me.rerere.rikkahub.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure (no Android dependencies) reference counter for active generations.
 *
 * Multiple conversations can generate concurrently, so the foreground service + wake/wifi locks must
 * start exactly on the 0 -> 1 edge and stop exactly on the 1 -> 0 edge. This class reports only those
 * edges as [Transition.STARTED] / [Transition.STOPPED]; every intermediate increment/decrement is
 * [Transition.NONE]. Callers map STARTED -> startForeground + acquire locks, STOPPED -> stopForeground
 * + release locks.
 *
 * Idempotent and concurrent-safe by construction: the edge detection is derived from the atomic
 * counter's pre/post values, so two threads racing acquire/release still observe exactly one STARTED
 * and one STOPPED across a balanced sequence. [release] clamps at 0 (never negative).
 *
 * JVM-testable unit.
 */
class GenerationActivityTracker {
    private val active = AtomicInteger(0)

    /** Current active-generation count. Exposed for assertions / diagnostics. */
    val count: Int get() = active.get()

    enum class Transition { STARTED, STOPPED, NONE }

    /** @return [Transition.STARTED] only on the 0 -> 1 edge, otherwise [Transition.NONE]. */
    fun acquire(): Transition {
        val after = active.incrementAndGet()
        return if (after == 1) Transition.STARTED else Transition.NONE
    }

    /**
     * Decrement, clamped at 0 so an over-release never drives the count negative.
     * @return [Transition.STOPPED] only on the 1 -> 0 edge, otherwise [Transition.NONE].
     */
    fun release(): Transition {
        while (true) {
            val current = active.get()
            if (current <= 0) {
                // Already at zero: nothing to release. Clamp (stay at 0) and report no edge.
                return Transition.NONE
            }
            if (active.compareAndSet(current, current - 1)) {
                return if (current - 1 == 0) Transition.STOPPED else Transition.NONE
            }
        }
    }
}
