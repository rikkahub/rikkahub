package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CronJobSchedulerTest {

    private fun job(
        scheduleType: String = "cron",
        cron: String? = "0 9 * * *",
        atMs: Long? = null,
        timezone: String? = "America/New_York",
        startAt: Long? = null,
        endAt: Long? = null,
        maxRuns: Int? = null,
        runsSoFar: Int = 0,
        lastRun: Long? = null,
        enabled: Boolean = true,
    ) = ScheduledJobEntity(
        id = "j", name = "n", prompt = "p", assistantId = "a",
        scheduleType = scheduleType, atUnixMs = atMs, cronExpression = cron,
        timezone = timezone, startAtUnixMs = startAt, endAtUnixMs = endAt,
        maxRuns = maxRuns, runsSoFar = runsSoFar, lastRunAtMs = lastRun,
        enabled = enabled, createdAtMs = 0L,
    )

    @Test
    fun `disabled returns null`() {
        assertNull(CronJobScheduler.nextRunMs(job(enabled = false), nowMs = 0L))
    }

    @Test
    fun `once future returns its timestamp`() {
        val at = 1_800_000_000_000L
        assertEquals(at, CronJobScheduler.nextRunMs(job(scheduleType = "once", atMs = at), nowMs = 0L))
    }

    @Test
    fun `once already-fired returns null`() {
        val at = 100L
        assertNull(CronJobScheduler.nextRunMs(
            job(scheduleType = "once", atMs = at, lastRun = at + 1), nowMs = at + 100L))
    }

    @Test
    fun `cron simple daily 9am NY tz`() {
        // Basis: 2026-06-01 08:00 NY local → next is 2026-06-01 09:00 NY
        val zone = ZoneId.of("America/New_York")
        val basis = LocalDateTime.of(2026, 6, 1, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 6, 1, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, CronJobScheduler.nextRunMs(job(), nowMs = basis))
    }

    @Test
    fun `start_at_unix_ms in future delays first fire`() {
        val zone = ZoneId.of("America/New_York")
        val now = LocalDateTime.of(2026, 6, 1, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val startAt = LocalDateTime.of(2026, 6, 5, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 6, 5, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, CronJobScheduler.nextRunMs(job(startAt = startAt), nowMs = now))
    }

    @Test
    fun `end_at_unix_ms past returns null`() {
        val past = 100L
        assertNull(CronJobScheduler.nextRunMs(job(endAt = past), nowMs = past + 1000L))
    }

    @Test
    fun `max_runs reached returns null`() {
        assertNull(CronJobScheduler.nextRunMs(job(maxRuns = 5, runsSoFar = 5), nowMs = 0L))
    }

    @Test
    fun `DST forward skip-day next fire correct`() {
        // 0 2 * * * in America/New_York on the 2026 DST forward day (March 8 2026 2am
        // doesn't exist — clock jumps 2:00 → 3:00). Expect: skip that fire, next fire is
        // March 9 2am, NOT March 8 3am.
        val zone = ZoneId.of("America/New_York")
        // basis = March 7 2026 03:00 NY (well before the spring-forward)
        val basis = LocalDateTime.of(2026, 3, 7, 3, 0).atZone(zone).toInstant().toEpochMilli()
        val next = CronJobScheduler.nextRunMs(
            job(cron = "0 2 * * *"), nowMs = basis
        )!!
        // The very next 2am fire should be March 8 — but the wall-clock 2am doesn't exist
        // that day. cron-utils' behavior: returns the first valid clock match, which is
        // March 9 2am (the test passes if the next fire is on or after March 9). We assert
        // the date >= March 9.
        val expectedMin = LocalDateTime.of(2026, 3, 9, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue("DST forward should not fire on the skip-day", next >= expectedMin)
    }
}
