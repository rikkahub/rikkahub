package me.rerere.ai.runtime.schedule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure recurrence math (SPEC.md M1 / task T2). Zero `:app`/Android deps — JVM-only `java.time`
 * (minSdk 26 guarantees `java.time` without desugaring). This module owns the WHEN of a recurring
 * schedule: it is the single source of the `next_fire_at` the repository's `claimDue` writes, so its
 * correctness is the whole "coalesce missed windows to one fire" guarantee.
 *
 * Two cadence families:
 *  - MINUTES / HOURS: a fixed-millis grid anchored at `firstFireAt`. Wall-clock-exact; DST does not
 *    apply (a "every 30 min" interval is 30 minutes of elapsed time regardless of zone offset jumps).
 *  - DAYS: a calendar-day cadence computed IN the schedule's zone so a "daily 09:00" stays at 09:00
 *    local across a DST transition (the conservative read in the spec's Assumption 1). An optional
 *    [RecurrenceSpec.timeOfDay] anchors the local fire time; without it the fire keeps the anchor's
 *    own local time-of-day.
 */

/** The cadence unit of a [RecurrenceSpec]. v1 covers "every N minutes/hours/days" — no cron. */
@Serializable
enum class RecurrenceUnit {
    @SerialName("MINUTES") MINUTES,
    @SerialName("HOURS") HOURS,
    @SerialName("DAYS") DAYS,
}

/**
 * The v1 recurrence model: fire `every` [unit]s. [timeOfDay] (local "HH:mm") is honored only for
 * [RecurrenceUnit.DAYS]; it is ignored for MINUTES/HOURS, where the grid is anchored purely on
 * `firstFireAt`. This is the typed form persisted as the `recurrence_spec` JSON TEXT column.
 *
 * @param every interval count; MUST be >= 1 (the repository's minimum-interval gate enforces the
 *   wall-clock floor, but a zero/negative `every` is a malformed spec and is rejected here).
 * @param timeOfDay optional local wall time "HH:mm" (00:00..23:59) anchoring DAYS fires; null keeps
 *   the anchor's own local time.
 */
@Serializable
data class RecurrenceSpec(
    val every: Int,
    val unit: RecurrenceUnit,
    val timeOfDay: String? = null,
) {
    init {
        require(every >= 1) { "recurrence interval `every` must be >= 1, was $every" }
    }
}

object Recurrence {

    /**
     * The first occurrence STRICTLY after [now] (epoch millis, wall clock), computed on the
     * [spec]'s grid anchored at [firstFireAt] in [zone]. A candidate landing exactly on [now] is
     * rejected (the result is always `> now`), matching `claimDue`'s `nextFireAt > now` due-check —
     * a schedule fires on the next future window, never re-fires the window now sits on.
     *
     * [lastFiredAt] is accepted for signature parity with the persisted row (the repository passes
     * the whole row through) but does not change the result: the next future occurrence is a pure
     * function of the grid and [now]. Coalescing falls out for free — N skipped windows all collapse
     * to this single value because we jump straight to the first future occurrence.
     */
    fun nextOccurrenceAfter(
        spec: RecurrenceSpec,
        firstFireAt: Long,
        @Suppress("UNUSED_PARAMETER") lastFiredAt: Long?,
        zone: ZoneId,
        now: Long,
    ): Long = when (spec.unit) {
        RecurrenceUnit.MINUTES -> nextOnFixedGrid(firstFireAt, spec.every.toLong() * MILLIS_PER_MINUTE, now)
        RecurrenceUnit.HOURS -> nextOnFixedGrid(firstFireAt, spec.every.toLong() * MILLIS_PER_HOUR, now)
        RecurrenceUnit.DAYS -> nextDailyOccurrence(spec, firstFireAt, zone, now)
    }

    /**
     * Coalesce all windows missed since [lastFiredAt] into the single next fire. By the
     * `FIRE_ONCE_AND_COALESCE` policy this is exactly [nextOccurrenceAfter]: jumping to the first
     * occurrence strictly after [now] is the collapse — there is no separate "replay each window"
     * path to coalesce away. Kept as a named entry point so the repository and tests can express the
     * coalescing intent directly (and so the metamorphic property has a referent).
     */
    fun coalesceMissed(
        spec: RecurrenceSpec,
        firstFireAt: Long,
        lastFiredAt: Long?,
        zone: ZoneId,
        now: Long,
    ): Long = nextOccurrenceAfter(spec, firstFireAt, lastFiredAt, zone, now)

    /** Fixed-millis grid anchored at [anchor], step [stepMillis]; first point strictly after [now]. */
    private fun nextOnFixedGrid(anchor: Long, stepMillis: Long, now: Long): Long {
        if (now < anchor) return anchor
        // elapsed whole steps since the anchor, then +1 to land strictly after now even when now is
        // exactly on a grid point (elapsed divides evenly ⇒ floor == that point ⇒ +1 step forward).
        val steps = (now - anchor) / stepMillis
        return anchor + (steps + 1) * stepMillis
    }

    /**
     * Calendar-daily occurrence in [zone]. The local time-of-day is [RecurrenceSpec.timeOfDay] when
     * set, else the anchor's own local time. Walking forward by ONE calendar day at a time (rather
     * than adding fixed millis) is what makes a "daily 09:00" survive DST: 09:00 local is re-resolved
     * to its UTC instant on each day, so a spring-forward day is simply 23h after the prior fire.
     */
    private fun nextDailyOccurrence(spec: RecurrenceSpec, firstFireAt: Long, zone: ZoneId, now: Long): Long {
        val anchorZdt = Instant.ofEpochMilli(firstFireAt).atZone(zone)
        val fireTime: LocalTime = spec.timeOfDay?.let { LocalTime.parse(it) } ?: anchorZdt.toLocalTime()
        val stepDays = spec.every.toLong()
        // First candidate: the anchor's date at the fire time, in zone.
        var candidate: ZonedDateTime = anchorZdt.toLocalDate().atTime(fireTime).atZone(zone)
        // Closed-form initial jump: skip whole `every`-day strides up to (never past) now's local date
        // in O(1), so an arbitrarily old `now` — or a forward-skewed/corrupt clock — can never drive an
        // unbounded per-day loop at claim time. The stride count is computed on local dates; flooring
        // keeps the jump at or before now's date, and the DST-correcting tail loop below re-resolves the
        // local fire time so a "daily 09:00" stays at 09:00 across an offset transition.
        val daysToNow = ChronoUnit.DAYS.between(candidate.toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        if (daysToNow >= stepDays) {
            val wholeStrides = daysToNow / stepDays
            candidate = candidate.toLocalDate().plusDays(wholeStrides * stepDays).atTime(fireTime).atZone(zone)
        }
        // Tail correction: at most a couple of strides to land strictly after now (covers the same-day
        // fire time and any DST boundary the jump landed on).
        while (candidate.toInstant().toEpochMilli() <= now) {
            candidate = candidate.plusDays(stepDays).toLocalDate().atTime(fireTime).atZone(zone)
        }
        return candidate.toInstant().toEpochMilli()
    }

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
}
