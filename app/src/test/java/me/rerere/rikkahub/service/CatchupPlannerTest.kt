package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CatchupPlannerTest {

    private fun job(catchup: String, cron: String = "0 * * * *") = ScheduledJobEntity(
        id = "j", name = "n", prompt = "p", assistantId = "a",
        scheduleType = "cron", cronExpression = cron, timezone = "UTC",
        catchup = catchup, createdAtMs = 0L,
    )

    private val z = ZoneId.of("UTC")
    private fun ms(d: Int, h: Int) = LocalDateTime.of(2026, 6, d, h, 0).atZone(z).toInstant().toEpochMilli()

    @Test
    fun `skip policy with 5 missed produces zero fires plus 5 skipped rows`() {
        val plan = CatchupPlanner.plan(
            job = job("skip"),
            lastRunMs = ms(1, 8),     // last fire June 1 08:00
            nowMs = ms(1, 13)         // now June 1 13:00 → 5 missed: 9,10,11,12,13
        )
        assertEquals(0, plan.fireDelaysMs.size)
        assertEquals(5, plan.skippedCatchupCount)
    }

    @Test
    fun `fire_once empty missed produces zero fires`() {
        val plan = CatchupPlanner.plan(
            job = job("fire_once"),
            lastRunMs = ms(1, 12),
            nowMs = ms(1, 12),        // no time elapsed
        )
        assertEquals(0, plan.fireDelaysMs.size)
        assertEquals(0, plan.skippedCatchupCount)
    }

    @Test
    fun `fire_once 5 missed produces 1 fire and 4 skipped rows`() {
        val plan = CatchupPlanner.plan(
            job = job("fire_once"),
            lastRunMs = ms(1, 8),
            nowMs = ms(1, 13),
        )
        assertEquals(listOf(0L), plan.fireDelaysMs)
        assertEquals(4, plan.skippedCatchupCount)
    }

    @Test
    fun `fire_all 3 missed produces 3 staggered fires`() {
        val plan = CatchupPlanner.plan(
            job = job("fire_all"),
            lastRunMs = ms(1, 8),
            nowMs = ms(1, 11),        // 3 missed: 9, 10, 11
        )
        assertEquals(listOf(0L, 2_000L, 4_000L), plan.fireDelaysMs)
        assertEquals(0, plan.skippedCatchupCount)
    }

    @Test
    fun `fire_all 25 missed produces 20 fires plus 5 skipped (cap)`() {
        val plan = CatchupPlanner.plan(
            job = job("fire_all"),
            lastRunMs = ms(1, 0),
            nowMs = ms(2, 1),         // 25 missed: hours 1..25
        )
        assertEquals(20, plan.fireDelaysMs.size)
        assertEquals(5, plan.skippedCatchupCount)
    }
}
