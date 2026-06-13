package me.rerere.rikkahub.ui.pages.schedule

import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.schedule.Recurrence
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure summary formatter (SPEC.md M3 / task T5, SC4). Renders the human phrase the Schedule UI shows
 * under the create form and on each card — e.g. "Every 2 days at 09:00 — next run Mon 16 Jun 09:00
 * (Asia/Jakarta)". No Compose, no Android: a plain JVM function parallel to how [Recurrence] is pure,
 * so it is exercised by `:app:test...UnitTest` against real `java.time`.
 *
 * The load-bearing invariant: the next fire shown for a RECURRING schedule is computed by the SAME
 * [Recurrence.nextOccurrenceAfter] the repository's `claimDue` calls — never a second date-math
 * implementation here — so the preview equals what the worker will actually fire (SC4). For a
 * ONE_SHOT schedule the only fire is `firstFireAt`, shown verbatim with no recurrence math.
 *
 * @param spec the recurrence model; non-null iff [kind] == [ScheduleKind.RECURRING].
 * @param now epoch millis (injected for tests) — the preview's "next run" is relative to this.
 */
fun scheduleSummary(
    kind: ScheduleKind,
    spec: RecurrenceSpec?,
    firstFireAt: Long,
    timeZoneId: String,
    now: Long,
    locale: Locale = Locale.getDefault(),
): String {
    val zone = ZoneId.of(timeZoneId)
    val fireFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", locale)
    fun formatFire(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(fireFormatter)

    return when (kind) {
        ScheduleKind.ONE_SHOT -> "Once at ${formatFire(firstFireAt)} ($timeZoneId)"
        ScheduleKind.RECURRING -> {
            // SC4: derive the next fire from the single source of next-fire truth, not a local copy.
            // lastFiredAt = null per Assumption 6 — the preview shows the next fire from `now`,
            // matching how a freshly created schedule behaves; lastFiredAt does not affect the result.
            val requireSpec = requireNotNull(spec) { "RECURRING schedule requires a RecurrenceSpec" }
            // SC4: show exactly what the worker fires next, never a second date-math model. The
            // repository persists `nextFireAt = firstFireAt` and fires the FIRST window there verbatim;
            // only LATER windows advance on the grid (TaskScheduleRepository.claimDue). So while the
            // first fire is still in the future it IS the next fire — emit firstFireAt, not the grid
            // (which for a DAYS schedule whose firstFireAt time-of-day differs from `timeOfDay` would
            // report a different, later instant the worker does not fire first). Once firstFireAt has
            // passed, the next fire is the grid occurrence after `now`, matching the advanced nextFireAt.
            // lastFiredAt = null per Assumption 6: it does not affect nextOccurrenceAfter's result.
            val nextFire = if (firstFireAt > now) {
                firstFireAt
            } else {
                Recurrence.nextOccurrenceAfter(
                    spec = requireSpec,
                    firstFireAt = firstFireAt,
                    lastFiredAt = null,
                    zone = zone,
                    now = now,
                )
            }
            "${cadencePhrase(requireSpec)} — next run ${formatFire(nextFire)} ($timeZoneId)"
        }
    }
}

/** "Every 30 minutes" / "Every hour" / "Every 2 days at 09:00" — the recurrence cadence in words. */
private fun cadencePhrase(spec: RecurrenceSpec): String {
    val unitWord = when (spec.unit) {
        RecurrenceUnit.MINUTES -> if (spec.every == 1) "minute" else "minutes"
        RecurrenceUnit.HOURS -> if (spec.every == 1) "hour" else "hours"
        RecurrenceUnit.DAYS -> if (spec.every == 1) "day" else "days"
    }
    val interval = if (spec.every == 1) "Every $unitWord" else "Every ${spec.every} $unitWord"
    // timeOfDay only anchors DAYS fires (Recurrence ignores it for MINUTES/HOURS), so name it only there.
    return if (spec.unit == RecurrenceUnit.DAYS && spec.timeOfDay != null) {
        "$interval at ${spec.timeOfDay}"
    } else {
        interval
    }
}
