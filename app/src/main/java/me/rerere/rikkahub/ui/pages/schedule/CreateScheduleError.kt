package me.rerere.rikkahub.ui.pages.schedule

/**
 * A create-dialog field the inline rejection error can attach to, or [NONE] for a dialog-level line.
 * The dialog binds the matching field's red `supportingText`; [NONE] renders a dialog-level error row.
 */
enum class CreateScheduleField { PROMPT, EVERY, TIMEZONE, NONE }

/**
 * A repository rejection reason mapped to the field the user must fix. [message] is the repository's
 * verbatim reason (display text only — see [createScheduleError]); [field] is the best-effort target.
 */
data class CreateScheduleError(
    val field: CreateScheduleField,
    val message: String,
)

/**
 * M2 (task T3): map a [me.rerere.ai.runtime.contract.ScheduleMutationResult.Rejected] reason to the
 * create-dialog field it most likely refers to, so the dialog can render the reason as red
 * `supportingText` under the offending field instead of destroying the user's input with a toast.
 *
 * This is a UX projection, NOT control flow: the UI never branches on the reason and the repository
 * stays the single legality path. The match keys off the stable reason PREFIXES the repository emits
 * (`TaskScheduleRepository.createInTransaction`); an unrecognized reason (e.g. a per-owner cap breach,
 * which has no single offending field) falls back to [CreateScheduleField.NONE] so it surfaces
 * dialog-level rather than being misattributed to a field. The reason string is carried through
 * unchanged — never reworded — so the user sees exactly what the legality path reported.
 */
fun createScheduleError(reason: String): CreateScheduleError {
    val field = when {
        reason.startsWith("prompt is too long") -> CreateScheduleField.PROMPT
        reason.startsWith("recurring interval") -> CreateScheduleField.EVERY
        reason.startsWith("invalid timeZoneId") -> CreateScheduleField.TIMEZONE
        else -> CreateScheduleField.NONE
    }
    return CreateScheduleError(field, reason)
}
