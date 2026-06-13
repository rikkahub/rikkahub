package me.rerere.ai.runtime.schedule

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Pure-recurrence PBT (SPEC.md M1 / task T2). [Recurrence] owns the WHEN of a recurring schedule —
 * given a [RecurrenceSpec], its anchor (`firstFireAt`), the last fire, the schedule's IANA zone and
 * the current wall clock, it returns the first occurrence strictly AFTER now. The repository's
 * `claimDue` advances `next_fire_at` to this value, so its three load-bearing properties are pinned
 * here as hermetic JVM tests off any Room/Android surface:
 *
 *  - BOUNDARY: a candidate landing exactly on a boundary (now == an occurrence) is NOT returned;
 *    the result is strictly future (`> now`), matching `claimDue`'s `nextFireAt > now` due-check.
 *  - MONOTONICITY: the result always strictly advances past `now` (a schedule can never go backwards).
 *  - METAMORPHIC: coalescing K missed windows (one jump) == stepping one interval at a time until
 *    past `now` (`MisfirePolicy.FIRE_ONCE_AND_COALESCE` fires once, never N catch-up runs).
 */
class RecurrencePropertyTest {

    private val utc = ZoneId.of("UTC")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // ---- BOUNDARY: now exactly on an occurrence ⇒ the NEXT occurrence, never now itself ----
    @Test
    fun `exactly-on-boundary now returns the strictly-next occurrence`() {
        val spec = RecurrenceSpec(every = 30, unit = RecurrenceUnit.MINUTES)
        val first = 1_000_000L
        // now lands precisely on the 3rd occurrence (first + 3*30min); result must be the 4th.
        val onBoundary = first + 3 * 30 * minute
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = onBoundary, utc, now = onBoundary)
        assertEquals(first + 4 * 30 * minute, next)
        assertTrue("result must be strictly after now", next > onBoundary)
    }

    // ---- BOUNDARY: now just before an occurrence ⇒ that occurrence ----
    @Test
    fun `now one milli before an occurrence returns that occurrence`() {
        val spec = RecurrenceSpec(every = 1, unit = RecurrenceUnit.HOURS)
        val first = 500L
        val target = first + 5 * hour
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, utc, now = target - 1)
        assertEquals(target, next)
    }

    // ---- MONOTONICITY: result is ALWAYS strictly > now, for any spec/anchor/now ----
    @Test
    fun `result strictly advances past now for any interval anchor and now`() {
        runBlocking {
            checkAll(
                1000,
                Arb.int(1..120),                       // every
                Arb.long(0L..10_000_000_000L),         // firstFireAt
                Arb.long(0L..10_000_000_000L),         // now
            ) { every, first, now ->
                for (unit in RecurrenceUnit.entries) {
                    val spec = RecurrenceSpec(every = every, unit = unit)
                    val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, utc, now)
                    assertTrue("$unit/$every: next $next must be > now $now", next > now)
                }
            }
        }
    }

    // ---- MONOTONICITY: every result is itself a valid occurrence (on the anchored grid) ----
    @Test
    fun `result lands on the recurrence grid`() {
        runBlocking {
            checkAll(
                500,
                Arb.int(1..90),
                Arb.long(0L..5_000_000_000L),
                Arb.long(0L..5_000_000_000L),
            ) { every, first, now ->
                // MINUTES/HOURS are exact fixed-millis grids anchored at first; assert divisibility.
                for (unit in listOf(RecurrenceUnit.MINUTES, RecurrenceUnit.HOURS)) {
                    val spec = RecurrenceSpec(every = every, unit = unit)
                    val step = if (unit == RecurrenceUnit.MINUTES) every * minute else every * hour
                    val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, utc, now)
                    assertEquals("offset from anchor must be a whole multiple of the interval",
                        0L, (next - first) % step)
                }
            }
        }
    }

    // ---- METAMORPHIC: production == an INDEPENDENT naive step-loop oracle (one fire, coalesced) ----
    // [naiveNextOccurrence] steps ONE interval at a time and never calls [Recurrence], so this pins a
    // genuine two-implementations-agree relation rather than f(x) == f(x). It has teeth precisely
    // because the DAYS path jumps in closed form on the production side while the oracle crawls. It also
    // asserts the coalesce intent: FIRE_ONCE_AND_COALESCE collapses K missed windows to this one value.
    @Test
    fun `production next-occurrence equals an independent naive step oracle`() {
        runBlocking {
            checkAll(
                800,
                Arb.int(1..240),
                Arb.long(0L..5_000_000_000L),
                Arb.long(0L..5_000_000_000L),
            ) { every, first, now ->
                for (unit in RecurrenceUnit.entries) {
                    val spec = RecurrenceSpec(every = every, unit = unit)
                    val coalesced = Recurrence.coalesceMissed(spec, first, lastFiredAt = null, utc, now)
                    val oracle = naiveNextOccurrence(spec, first, utc, now)
                    assertEquals("$unit/$every: coalesce must match the independent step oracle", oracle, coalesced)
                    assertTrue("result must be strictly after now", coalesced > now)
                }
            }
        }
    }

    // ---- METAMORPHIC (DST zone): the DAYS closed-form jump must equal the naive crawl across offset
    // transitions. The UTC properties above never exercise DST, so the closed-form's "stride on local
    // dates, re-resolve fire time" claim is verified here over a window that spans spring-forward AND
    // fall-back in America/New_York. ----
    @Test
    fun `daily closed-form jump matches the naive crawl in a DST zone`() {
        val ny = ZoneId.of("America/New_York")
        val anchor = java.time.ZonedDateTime.parse("2026-01-01T09:00:00-05:00").toInstant().toEpochMilli()
        runBlocking {
            checkAll(
                500,
                Arb.int(1..14),                       // every N days
                Arb.long(0L..(400L * day)),           // now within ~400 days of the anchor
            ) { every, offset ->
                val spec = RecurrenceSpec(every = every, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00")
                val now = anchor + offset
                val production = Recurrence.nextOccurrenceAfter(spec, anchor, lastFiredAt = null, ny, now)
                val oracle = naiveNextOccurrence(spec, anchor, ny, now)
                assertEquals("every=$every offset=$offset: closed-form must equal the naive crawl", oracle, production)
            }
        }
    }

    // ---- METAMORPHIC (concrete): 5 skipped daily windows collapse to one future fire ----
    @Test
    fun `five skipped daily windows collapse to a single next fire`() {
        val spec = RecurrenceSpec(every = 1, unit = RecurrenceUnit.DAYS)
        val first = day // anchor at day 1 boundary in UTC
        // Process was dead for 5 days: now is 5.5 days past first, last fire was at first.
        val now = first + 5 * day + hour * 12
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = first, utc, now)
        // The first DAILY occurrence strictly after now is day 7 (first + 6*day), NOT day 2.
        assertEquals(first + 6 * day, next)
    }

    // ---- DAYS with time-of-day anchor: fires at the anchored local time, DST-safe ----
    @Test
    fun `daily schedule with time-of-day anchor fires at that local time`() {
        val ny = ZoneId.of("America/New_York")
        val spec = RecurrenceSpec(every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00")
        // 2026-03-07T00:00:00Z anchor; now is mid-day on 2026-03-09 (after a DST spring-forward 03-08).
        val first = java.time.ZonedDateTime.parse("2026-03-07T09:00:00-05:00").toInstant().toEpochMilli()
        val now = java.time.ZonedDateTime.parse("2026-03-09T12:00:00-04:00").toInstant().toEpochMilli()
        val next = Recurrence.nextOccurrenceAfter(spec, first, lastFiredAt = null, ny, now)
        // Next 09:00 New York after now is 2026-03-10T09:00 EDT — still 09:00 local despite DST.
        val expected = java.time.ZonedDateTime.parse("2026-03-10T09:00:00-04:00").toInstant().toEpochMilli()
        assertEquals(expected, next)
    }

    /**
     * Independent reference oracle for the metamorphic properties: the first occurrence strictly after
     * [now], computed by naive forward stepping ONE interval at a time from the anchor. It deliberately
     * does NOT call [Recurrence] and never uses a closed-form jump, so agreement with production is a
     * real two-implementation cross-check whose whole purpose is to catch a divergence in the DAYS
     * closed-form.
     */
    private fun naiveNextOccurrence(spec: RecurrenceSpec, first: Long, zone: ZoneId, now: Long): Long =
        when (spec.unit) {
            RecurrenceUnit.MINUTES, RecurrenceUnit.HOURS -> {
                val step = if (spec.unit == RecurrenceUnit.MINUTES) spec.every * minute else spec.every * hour
                var c = first
                while (c <= now) c += step
                c
            }
            RecurrenceUnit.DAYS -> {
                val anchorZdt = java.time.Instant.ofEpochMilli(first).atZone(zone)
                val fireTime = spec.timeOfDay?.let { java.time.LocalTime.parse(it) } ?: anchorZdt.toLocalTime()
                val stepDays = spec.every.toLong()
                var candidate = anchorZdt.toLocalDate().atTime(fireTime).atZone(zone)
                while (candidate.toInstant().toEpochMilli() <= now) {
                    candidate = candidate.plusDays(stepDays).toLocalDate().atTime(fireTime).atZone(zone)
                }
                candidate.toInstant().toEpochMilli()
            }
        }
}
