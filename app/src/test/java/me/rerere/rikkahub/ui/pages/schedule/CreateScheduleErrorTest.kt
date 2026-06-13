package me.rerere.rikkahub.ui.pages.schedule

import me.rerere.rikkahub.data.repository.TaskScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M2 (task T3): a repository [me.rerere.ai.runtime.contract.ScheduleMutationResult.Rejected] keeps the
 * create dialog open and renders its reason inline. The reason is display text only — the UI never
 * parses it for control flow — but it IS mapped to the most likely offending field where the string is
 * recognizable (spec assumption 4) so the red supportingText lands under the field the user must fix,
 * falling back to a dialog-level line for anything unrecognized.
 *
 * This is the pure, Android-free mapping; the dialog binds [CreateScheduleError.field] to the matching
 * field's supportingText (or, for [CreateScheduleField.NONE], a dialog-level error line).
 */
class CreateScheduleErrorTest {

    @Test
    fun over_length_prompt_reason_maps_to_the_prompt_field() {
        val reason = "prompt is too long: 8001 > ${TaskScheduleRepository.MAX_PROMPT_CHARS}"

        val mapped = createScheduleError(reason)

        assertEquals(CreateScheduleField.PROMPT, mapped.field)
        assertEquals(reason, mapped.message)
    }

    @Test
    fun sub_minimum_interval_reason_maps_to_the_every_field() {
        val reason = "recurring interval 60000 ms is below the minimum 900000 ms"

        val mapped = createScheduleError(reason)

        assertEquals(CreateScheduleField.EVERY, mapped.field)
        assertEquals(reason, mapped.message)
    }

    @Test
    fun invalid_timezone_reason_maps_to_the_timezone_field() {
        val reason = "invalid timeZoneId: Mars/Olympus"

        val mapped = createScheduleError(reason)

        assertEquals(CreateScheduleField.TIMEZONE, mapped.field)
        assertEquals(reason, mapped.message)
    }

    @Test
    fun unrecognized_reason_falls_back_to_a_dialog_level_line() {
        // A per-owner cap breach has no single offending field — it must surface dialog-level, not be
        // misattributed to prompt/every/timezone.
        val reason = "active schedule cap reached for owner USER"

        val mapped = createScheduleError(reason)

        assertEquals(CreateScheduleField.NONE, mapped.field)
        assertEquals(reason, mapped.message)
    }
}
