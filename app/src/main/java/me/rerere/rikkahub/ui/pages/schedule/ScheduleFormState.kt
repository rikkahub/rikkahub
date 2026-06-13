package me.rerere.rikkahub.ui.pages.schedule

import kotlinx.serialization.json.Json
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.rikkahub.data.repository.TaskScheduleRepository
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * A form field a mirrored-validation error attaches to (SPEC.md M4 / task T7). The create dialog
 * binds the matching field's red `supportingText` to the message keyed here. Distinct from
 * [CreateScheduleField] (which maps a *repository* rejection back to a field): this enum keys the
 * *pre-submit* guards the form computes itself, so it carries the extra fields the form owns
 * ([UNIT], [TIME_OF_DAY]) that a repository reason never names.
 */
enum class ScheduleField { PROMPT, EVERY, UNIT, TIMEZONE, TIME_OF_DAY }

/**
 * Pure form-state holder for the create-schedule dialog (SPEC.md M4 / task T7, SC3). Plain JVM data
 * class — no Compose, no Android — parallel to how [me.rerere.ai.runtime.schedule.Recurrence] is
 * pure, so it is exercised by `:app:test...UnitTest`.
 *
 * [validate] MIRRORS — it does not replace — the legality gates in [TaskScheduleRepository]. The
 * repository remains the single source of truth; these guards exist so the UI cannot OFFER a value
 * the repository will reject (the create button is enabled iff [validate] is empty). The mirrored
 * gates, keyed to the repository constant each one tracks:
 *
 *  - prompt non-blank AND trimmed `length <= ` [TaskScheduleRepository.MAX_PROMPT_CHARS] (the repo
 *    counts the stored, trimmed prompt, so this guard counts `prompt.trim().length` to match),
 *  - a RECURRING effective interval `>= ` [TaskScheduleRepository.MIN_RECURRENCE_INTERVAL_MILLIS]
 *    (so MINUTES requires `every >= 15`; `every = 1, MINUTES` is unsubmittable),
 *  - a valid IANA [timeZoneId] (`ZoneId.of`),
 *  - a valid `HH:mm` [timeOfDay] for DAYS (`LocalTime.parse`); ignored for MINUTES/HOURS, exactly as
 *    [me.rerere.ai.runtime.schedule.Recurrence] ignores it off the DAYS path.
 *
 * Caps are NOT mirrored: they depend on rows the form cannot see (other conversations / the owner
 * class), so a cap breach surfaces only as the repository's `Rejected` reason inline (M2), never as
 * a pre-submit guard that would have to guess at unseen state.
 *
 * @param firstFireAt epoch millis, seeded from a real picker (default now+1h at the call site).
 * @param timeOfDay local "HH:mm"; honored only when [kind] is RECURRING with [unit] == DAYS.
 * @param timeZoneId IANA zone id; defaults to the device zone at the call site, editable.
 */
data class ScheduleFormState(
    val prompt: String = "",
    val kind: ScheduleKind = ScheduleKind.ONE_SHOT,
    val every: Int = 1,
    val unit: RecurrenceUnit = RecurrenceUnit.HOURS,
    val firstFireAt: Long,
    val timeOfDay: String? = null,
    val timeZoneId: String,
) {
    /** Field-keyed errors; an empty map == submittable. Mirrors [TaskScheduleRepository] gates. */
    fun validate(now: Long): Map<ScheduleField, String> {
        val errors = mutableMapOf<ScheduleField, String>()

        // Mirror the value the repository judges: it counts the STORED prompt, which toDraft() trims
        // (this.toDraft() -> draft.prompt = prompt.trim(); TaskScheduleRepository counts
        // draft.prompt.length). Guarding the raw length here would reject a whitespace-padded prompt
        // whose trimmed draft the repository Accepts — the SC3 mirror-drift this enum exists to prevent.
        val trimmedLength = prompt.trim().length
        when {
            prompt.isBlank() ->
                errors[ScheduleField.PROMPT] = "Prompt is required"
            trimmedLength > TaskScheduleRepository.MAX_PROMPT_CHARS ->
                errors[ScheduleField.PROMPT] =
                    "Prompt is too long: $trimmedLength > ${TaskScheduleRepository.MAX_PROMPT_CHARS}"
        }

        if (!isValidZoneId(timeZoneId)) {
            errors[ScheduleField.TIMEZONE] = "Not a valid timezone"
        }

        if (kind == ScheduleKind.RECURRING) {
            val intervalMillis = intervalMillis()
            when {
                every < 1 ->
                    errors[ScheduleField.EVERY] = "Interval must be at least 1"
                intervalMillis < TaskScheduleRepository.MIN_RECURRENCE_INTERVAL_MILLIS ->
                    errors[ScheduleField.EVERY] = "Minimum interval is 15 minutes"
            }
            // timeOfDay anchors DAYS fires only (Recurrence.kt:34); MINUTES/HOURS ignore it, so a
            // stale value left over from a prior DAYS selection must not block submission.
            if (unit == RecurrenceUnit.DAYS && timeOfDay != null && !isValidLocalTime(timeOfDay)) {
                errors[ScheduleField.TIME_OF_DAY] = "Use HH:mm (24-hour)"
            }
        }

        return errors
    }

    /**
     * Project this form into the [ScheduleDraft] the dialog hands [ScheduleVM.createSchedule] (SPEC.md
     * M4 / task T9). This is the SINGLE form→draft mapping: the create dialog and the SC3 invariant
     * test both call it, so the value the repository judges is exactly the value the test proves
     * acceptable — there is no second hand-rolled projection that could drift from the validated form.
     *
     * - [ScheduleDraft.targetAssistantId] is [Uuid.NIL]; the VM stamps the screen's bound assistant
     *   before the draft reaches the repository (the UI never aims at a foreign assistant).
     * - The prompt is trimmed (matching the create gate, which counts the stored prompt's length).
     * - [recurrenceSpec] is the JSON-encoded [RecurrenceSpec] for RECURRING, null for ONE_SHOT;
     *   [timeOfDay] is carried only when [unit] is DAYS (it is meaningless off the DAYS path —
     *   Recurrence.kt:34 — so a stale value from a prior DAYS selection is dropped here, exactly as
     *   [validate] ignores it).
     *
     * @param json the serializer for the recurrence spec; the call site passes the app's [Json].
     */
    fun toDraft(json: Json = Json): ScheduleDraft {
        val spec = if (kind == ScheduleKind.RECURRING) {
            val anchoredTimeOfDay = timeOfDay.takeIf { unit == RecurrenceUnit.DAYS }
            json.encodeToString(RecurrenceSpec(every = every, unit = unit, timeOfDay = anchoredTimeOfDay))
        } else {
            null
        }
        return ScheduleDraft(
            targetAssistantId = Uuid.NIL,
            prompt = prompt.trim(),
            kind = kind,
            firstFireAt = firstFireAt,
            timeZoneId = timeZoneId,
            recurrenceSpec = spec,
        )
    }

    private fun intervalMillis(): Long = when (unit) {
        RecurrenceUnit.MINUTES -> every.toLong() * MILLIS_PER_MINUTE
        RecurrenceUnit.HOURS -> every.toLong() * MILLIS_PER_HOUR
        RecurrenceUnit.DAYS -> every.toLong() * MILLIS_PER_DAY
    }

    private fun isValidZoneId(id: String): Boolean = runCatching { ZoneId.of(id) }.isSuccess

    private fun isValidLocalTime(value: String): Boolean = runCatching { LocalTime.parse(value) }.isSuccess

    private companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
        private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
    }
}

/**
 * The minimum LEGAL `every` for [unit] (SPEC.md M3 / task T8). The create form's -/+ stepper floors
 * its value here so it can never produce a sub-floor draft the repository rejects — the stepper's
 * lower bound and [ScheduleFormState.validate]'s interval gate are derived from the SAME
 * [TaskScheduleRepository.MIN_RECURRENCE_INTERVAL_MILLIS], so they cannot drift apart. Computed by
 * dividing the floor by the unit's millis and rounding UP (so MINUTES needs 15; HOURS/DAYS already
 * clear the 15-minute floor at every = 1).
 */
fun minEveryFor(unit: RecurrenceUnit): Int {
    val unitMillis = when (unit) {
        RecurrenceUnit.MINUTES -> 60_000L
        RecurrenceUnit.HOURS -> 60 * 60_000L
        RecurrenceUnit.DAYS -> 24 * 60 * 60_000L
    }
    val floorByInterval =
        ((TaskScheduleRepository.MIN_RECURRENCE_INTERVAL_MILLIS + unitMillis - 1) / unitMillis).toInt()
    return maxOf(1, floorByInterval)
}
