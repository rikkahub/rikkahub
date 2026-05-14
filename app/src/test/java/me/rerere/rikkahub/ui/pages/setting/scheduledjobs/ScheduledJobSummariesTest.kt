package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for [summariseSchedule] and [modeLabel] - the schedule pretty-printer
 * shared by the scheduled-jobs list row and the detail screen header. These functions have
 * no Android dependencies, so we can pin every cron branch directly.
 *
 * Time-relative formatting (`formatAbsoluteTime` / `formatAbsoluteForDetail`) is locale- and
 * clock-dependent and intentionally not asserted here; only the deterministic cron and mode
 * branches are covered.
 */
class ScheduledJobSummariesTest {

    private fun job(
        scheduleType: String,
        cronExpression: String? = null,
        atUnixMs: Long? = null,
        mode: String = "llm",
    ) = ScheduledJobEntity(
        id = "id",
        name = "name",
        assistantId = "assistant",
        scheduleType = scheduleType,
        cronExpression = cronExpression,
        atUnixMs = atUnixMs,
        createdAtMs = 0L,
        mode = mode,
    )

    @Test fun `once with no time set`() {
        assertEquals("once (no time set)", summariseSchedule(job("once", atUnixMs = null)))
    }

    @Test fun `cron macro shortcuts`() {
        assertEquals("every hour", summariseSchedule(job("cron", "@hourly")))
        assertEquals("every day at 00:00", summariseSchedule(job("cron", "@daily")))
        assertEquals("every day at 00:00", summariseSchedule(job("cron", "@midnight")))
        assertEquals("every Sunday at 00:00", summariseSchedule(job("cron", "@weekly")))
        assertEquals("first of every month at 00:00", summariseSchedule(job("cron", "@monthly")))
        assertEquals("every Jan 1 at 00:00", summariseSchedule(job("cron", "@yearly")))
        assertEquals("every Jan 1 at 00:00", summariseSchedule(job("cron", "@annually")))
    }

    @Test fun `cron at-every passthrough`() {
        assertEquals("every 30m", summariseSchedule(job("cron", "@every 30m")))
        assertEquals("every 2h", summariseSchedule(job("cron", "@every 2h")))
    }

    @Test fun `cron every N minutes`() {
        assertEquals("every minute", summariseSchedule(job("cron", "*/1 * * * *")))
        assertEquals("every 15 min", summariseSchedule(job("cron", "*/15 * * * *")))
    }

    @Test fun `cron every N hours`() {
        assertEquals("every hour", summariseSchedule(job("cron", "0 */1 * * *")))
        assertEquals("every 6 hours", summariseSchedule(job("cron", "0 */6 * * *")))
    }

    @Test fun `cron every day at fixed time`() {
        assertEquals("every day at 09:00", summariseSchedule(job("cron", "0 9 * * *")))
        assertEquals("every day at 23:05", summariseSchedule(job("cron", "5 23 * * *")))
    }

    @Test fun `cron specific weekdays by name`() {
        assertEquals(
            "every Mon, Wed, Fri at 09:00",
            summariseSchedule(job("cron", "0 9 * * MON,WED,FRI")),
        )
    }

    @Test fun `cron specific weekdays by number`() {
        assertEquals(
            "every Mon, Wed, Fri at 09:00",
            summariseSchedule(job("cron", "0 9 * * 1,3,5")),
        )
    }

    @Test fun `cron unrecognised expression falls back to custom`() {
        assertEquals("custom: 0 9 1-15 * *", summariseSchedule(job("cron", "0 9 1-15 * *")))
        assertEquals("custom: not a cron", summariseSchedule(job("cron", "not a cron")))
        assertEquals("custom: ", summariseSchedule(job("cron", "")))
        assertEquals("custom: ", summariseSchedule(job("cron", cronExpression = null)))
    }

    @Test fun `cron range of weekdays is not pretty-printed`() {
        assertEquals("custom: 0 9 * * MON-FRI", summariseSchedule(job("cron", "0 9 * * MON-FRI")))
    }

    @Test fun `unknown schedule type echoes the raw value`() {
        assertEquals("interval", summariseSchedule(job("interval")))
    }

    @Test fun `mode label mapping`() {
        assertEquals("LLM-driven", modeLabel(job("once", mode = "llm")))
        assertEquals("direct (no LLM)", modeLabel(job("once", mode = "direct")))
        assertEquals("future-mode", modeLabel(job("once", mode = "future-mode")))
    }
}
