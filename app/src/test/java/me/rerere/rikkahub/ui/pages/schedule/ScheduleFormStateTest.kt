package me.rerere.rikkahub.ui.pages.schedule

import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid
import java.util.TimeZone

/**
 * Pure-validation tests (SPEC.md M4 / task T7, SC3). [ScheduleFormState.validate] mirrors — does NOT
 * replace — the gates in [me.rerere.rikkahub.data.repository.TaskScheduleRepository]:
 *
 *  - prompt non-blank AND length <= 8000 (`MAX_PROMPT_CHARS`),
 *  - a RECURRING effective interval >= 15 min (`MIN_RECURRENCE_INTERVAL_MILLIS`), so MINUTES requires
 *    `every >= 15` (`every = 1, MINUTES` is unsubmittable — the 15-min floor),
 *  - a valid IANA `timeZoneId` (`ZoneId.of`),
 *  - a valid `HH:mm` `timeOfDay` for DAYS (`LocalTime.parse`).
 *
 * An empty error map == submittable; the create button binds to it. The repository stays the single
 * source of truth — these guards exist so the UI cannot OFFER a value the repository will reject.
 *
 * BOUNDARY suite (SC3): the inputs that sit on each gate's edge, where an off-by-one or a missing
 * guard would let a sub-floor value through.
 */
class ScheduleFormStateTest {

    private val now = 1_700_000_000_000L
    private val targetAssistantId = Uuid.random()
    private val defaultZone: String = TimeZone.getDefault().id
    private val futureFire = now + 60 * 60 * 1000L // now + 1h

    private fun oneShot(prompt: String = "do a thing") = ScheduleFormState(
        prompt = prompt,
        kind = ScheduleKind.ONE_SHOT,
        firstFireAt = futureFire,
        targetAssistantId = targetAssistantId,
        timeZoneId = defaultZone,
    )

    private fun recurring(
        every: Int,
        unit: RecurrenceUnit,
        prompt: String = "do a thing",
        timeOfDay: String? = null,
        timeZoneId: String = defaultZone,
    ) = ScheduleFormState(
        prompt = prompt,
        kind = ScheduleKind.RECURRING,
        every = every,
        unit = unit,
        firstFireAt = futureFire,
        timeOfDay = timeOfDay,
        targetAssistantId = targetAssistantId,
        timeZoneId = timeZoneId,
    )

    // ---- prompt bound (mirrors MAX_PROMPT_CHARS = 8000) -----------------------------------------

    @Test
    fun `blank prompt is invalid`() {
        val errors = oneShot(prompt = "   ").validate(now)
        assertTrue("blank prompt must flag PROMPT: $errors", errors.containsKey(ScheduleField.PROMPT))
    }

    @Test
    fun `prompt length 8000 is valid`() {
        val errors = oneShot(prompt = "a".repeat(8000)).validate(now)
        assertFalse("prompt of exactly 8000 chars must NOT flag PROMPT: $errors", errors.containsKey(ScheduleField.PROMPT))
    }

    @Test
    fun `prompt length 8001 is invalid`() {
        val errors = oneShot(prompt = "a".repeat(8001)).validate(now)
        assertTrue("prompt of 8001 chars must flag PROMPT: $errors", errors.containsKey(ScheduleField.PROMPT))
    }

    @Test
    fun `prompt whose raw length exceeds the cap but trims under it is submittable (SC3 mirror)`() {
        // SC3: validate() must judge the SAME value toDraft() submits. The repository gates on the
        // stored (trimmed) prompt length, so a prompt of <=8000 real chars padded with surrounding
        // whitespace to a raw length >8000 trims to a draft the repo Accepts — the UI must not reject
        // it. Guarding raw `prompt.length` here would block a draft the repository would have taken.
        val padded = "  " + "a".repeat(8000) + "   " // raw length 8005, trims to exactly 8000
        val errors = oneShot(prompt = padded).validate(now)
        assertFalse(
            "a prompt that trims to 8000 must NOT flag PROMPT (repo accepts the trimmed draft): $errors",
            errors.containsKey(ScheduleField.PROMPT),
        )
    }

    @Test
    fun `prompt whose trimmed length exceeds the cap is invalid`() {
        // The other edge of the same mirror: trimming must not let an over-cap prompt through. 8001
        // real chars trims to 8001, which the repository rejects, so validate() must flag it too.
        val errors = oneShot(prompt = "  " + "a".repeat(8001) + "  ").validate(now)
        assertTrue(
            "a prompt that trims to 8001 must flag PROMPT (repo rejects the trimmed draft): $errors",
            errors.containsKey(ScheduleField.PROMPT),
        )
    }

    // ---- minimum recurring interval (mirrors MIN_RECURRENCE_INTERVAL_MILLIS = 15 min) ------------

    @Test
    fun `every 1 unit MINUTES is invalid (below the 15-minute floor)`() {
        val errors = recurring(every = 1, unit = RecurrenceUnit.MINUTES).validate(now)
        assertTrue("1 minute breaches the 15-min floor: $errors", errors.containsKey(ScheduleField.EVERY))
    }

    @Test
    fun `every 14 unit MINUTES is invalid (below the 15-minute floor)`() {
        val errors = recurring(every = 14, unit = RecurrenceUnit.MINUTES).validate(now)
        assertTrue("14 minutes breaches the 15-min floor: $errors", errors.containsKey(ScheduleField.EVERY))
    }

    @Test
    fun `every 15 unit MINUTES is valid (exactly the 15-minute floor)`() {
        val errors = recurring(every = 15, unit = RecurrenceUnit.MINUTES).validate(now)
        assertFalse("15 minutes meets the floor, must be submittable: $errors", errors.containsKey(ScheduleField.EVERY))
        assertTrue("15-min recurring must be fully submittable: $errors", errors.isEmpty())
    }

    @Test
    fun `every 0 is invalid (malformed interval)`() {
        val errors = recurring(every = 0, unit = RecurrenceUnit.HOURS).validate(now)
        assertTrue("every < 1 must flag EVERY: $errors", errors.containsKey(ScheduleField.EVERY))
    }

    @Test
    fun `every 1 unit HOURS is valid (1h is above the 15-min floor)`() {
        val errors = recurring(every = 1, unit = RecurrenceUnit.HOURS).validate(now)
        assertTrue("1 hour recurring must be fully submittable: $errors", errors.isEmpty())
    }

    // ---- timezone gate (mirrors ZoneId.of) ------------------------------------------------------

    @Test
    fun `bad timezone is invalid`() {
        val errors = oneShot().copy(timeZoneId = "Not/AZone").validate(now)
        assertTrue("unparseable zone must flag TIMEZONE: $errors", errors.containsKey(ScheduleField.TIMEZONE))
    }

    @Test
    fun `valid IANA timezone is accepted`() {
        val errors = oneShot().copy(timeZoneId = "Asia/Jakarta").validate(now)
        assertFalse("valid zone must NOT flag TIMEZONE: $errors", errors.containsKey(ScheduleField.TIMEZONE))
    }

    // ---- daily timeOfDay gate (mirrors LocalTime.parse, DAYS only) -------------------------------

    @Test
    fun `bad timeOfDay for DAYS is invalid`() {
        val errors = recurring(every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = "25:99").validate(now)
        assertTrue("bad HH:mm must flag TIME_OF_DAY: $errors", errors.containsKey(ScheduleField.TIME_OF_DAY))
    }

    @Test
    fun `valid timeOfDay for DAYS is accepted`() {
        val errors = recurring(every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = "09:00").validate(now)
        assertTrue("valid daily schedule must be submittable: $errors", errors.isEmpty())
    }

    @Test
    fun `null timeOfDay for DAYS is accepted (anchor time is used)`() {
        val errors = recurring(every = 1, unit = RecurrenceUnit.DAYS, timeOfDay = null).validate(now)
        assertTrue("null timeOfDay is legal for DAYS: $errors", errors.isEmpty())
    }

    @Test
    fun `bad timeOfDay is ignored for MINUTES (only DAYS honors it)`() {
        // Recurrence.kt:34 — timeOfDay anchors DAYS fires only; MINUTES/HOURS ignore it, so the UI
        // must not block submission on a stale timeOfDay left over from a DAYS selection.
        val errors = recurring(every = 30, unit = RecurrenceUnit.MINUTES, timeOfDay = "25:99").validate(now)
        assertFalse("MINUTES must not honor timeOfDay: $errors", errors.containsKey(ScheduleField.TIME_OF_DAY))
    }

    // ---- submittability ------------------------------------------------------------------------

    @Test
    fun `a well-formed one-shot is submittable`() {
        assertTrue("a valid one-shot must yield no errors", oneShot().validate(now).isEmpty())
    }

    // ---- stepper floor per unit (M3 / task T8) --------------------------------------------------
    // The create form's -/+ stepper must floor `every` at the minimum LEGAL value for the chosen
    // unit, so the stepper can never produce a sub-floor draft the repository would reject. MINUTES'
    // floor is 15 (mirrors MIN_RECURRENCE_INTERVAL_MILLIS = 15 min); HOURS/DAYS floor at 1.

    @Test
    fun `minimum every for MINUTES is 15 (mirrors the 15-minute interval floor)`() {
        assertEquals(15, minEveryFor(RecurrenceUnit.MINUTES))
    }

    @Test
    fun `minimum every for HOURS is 1`() {
        assertEquals(1, minEveryFor(RecurrenceUnit.HOURS))
    }

    @Test
    fun `minimum every for DAYS is 1`() {
        assertEquals(1, minEveryFor(RecurrenceUnit.DAYS))
    }

    @Test
    fun `the minimum every for each unit is itself submittable for a recurring form`() {
        // Property: stepping to the floor for any unit yields a value the repository accepts, so the
        // stepper's lower bound and the validate() interval gate agree (no off-by-one window where
        // the stepper sits on a value validate() still rejects).
        for (unit in RecurrenceUnit.entries) {
            val errors = recurring(every = minEveryFor(unit), unit = unit).validate(now)
            assertFalse(
                "every = floor for $unit must not flag EVERY: $errors",
                errors.containsKey(ScheduleField.EVERY),
            )
        }
    }
}
