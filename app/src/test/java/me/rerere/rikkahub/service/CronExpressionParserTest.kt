package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CronExpressionParserTest {

    @Test
    fun `parses 5-field expression`() {
        val r = CronExpressionParser.parse("0 9 * * MON-FRI")
        assertTrue(r.isSuccess)
    }

    @Test
    fun `parses step alias`() {
        assertTrue(CronExpressionParser.parse("*/15 * * * *").isSuccess)
    }

    @Test
    fun `parses every alias`() {
        assertTrue(CronExpressionParser.parse("@every 30m").isSuccess)
        assertTrue(CronExpressionParser.parse("@every 60s").isSuccess)
        assertTrue(CronExpressionParser.parse("@every 2h").isSuccess)
    }

    @Test
    fun `rejects @every 30s — sub-minute not supported`() {
        // Previously coerced to */1 * * * *; now correctly rejected.
        val r = CronExpressionParser.parse("@every 30s")
        assertTrue("@every 30s must return failure (sub-minute not supported)", r.isFailure)
    }

    @Test
    fun `@every 60s produces every-minute cron`() {
        val r = CronExpressionParser.parse("@every 60s")
        assertTrue("@every 60s should parse successfully", r.isSuccess)
        // 60s / 60 = 1 → */1 * * * *
        val next1 = CronExpressionParser.nextExecution(
            r.getOrThrow(),
            ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        )
        assertNotNull(next1)
        assertEquals("should fire at minute 1", 1, next1!!.minute)
    }

    @Test
    fun `@every 3600s produces hourly cron`() {
        val r = CronExpressionParser.parse("@every 3600s")
        assertTrue("@every 3600s should parse successfully", r.isSuccess)
        val basis = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        val next = CronExpressionParser.nextExecution(r.getOrThrow(), basis)
        assertNotNull(next)
        // 0 */1 * * * fires at the top of every hour
        assertEquals("should fire at minute 0", 0, next!!.minute)
        assertEquals("should advance by 1 hour", 1, next.hour)
    }

    @Test
    fun `@every 7200s produces every-2-hours cron`() {
        val r = CronExpressionParser.parse("@every 7200s")
        assertTrue("@every 7200s should parse successfully", r.isSuccess)
        val basis = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        val next = CronExpressionParser.nextExecution(r.getOrThrow(), basis)
        assertNotNull(next)
        assertEquals("should fire at minute 0", 0, next!!.minute)
        assertEquals("should advance by 2 hours", 2, next.hour)
    }

    @Test
    fun `@every 86400s produces daily-at-midnight cron`() {
        val r = CronExpressionParser.parse("@every 86400s")
        assertTrue("@every 86400s should parse successfully", r.isSuccess)
        val basis = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        val next = CronExpressionParser.nextExecution(r.getOrThrow(), basis)
        assertNotNull(next)
        assertEquals("should fire at hour 0", 0, next!!.hour)
        assertEquals("should fire at minute 0", 0, next.minute)
        assertEquals("should advance by 1 day", 2, next.dayOfMonth)
    }

    @Test
    fun `parses standard nicknames`() {
        listOf("@hourly", "@daily", "@weekly", "@monthly", "@yearly").forEach {
            assertTrue("nickname $it should parse", CronExpressionParser.parse(it).isSuccess)
        }
    }

    @Test
    fun `rejects malformed expression`() {
        val r = CronExpressionParser.parse("not a cron")
        assertTrue(r.isFailure)
    }

    @Test
    fun `cache returns same instance on second parse`() {
        val a = CronExpressionParser.parse("0 9 * * *").getOrThrow()
        val b = CronExpressionParser.parse("0 9 * * *").getOrThrow()
        assertTrue("LRU cache hit expected", a === b)
    }

    @Test
    fun `nextExecution returns expected UTC instant`() {
        val cron = CronExpressionParser.parse("0 9 * * *").getOrThrow()
        val basis = ZonedDateTime.of(2026, 6, 1, 8, 0, 0, 0, ZoneId.of("UTC"))
        val next = CronExpressionParser.nextExecution(cron, basis)
        assertNotNull(next)
        assertEquals(9, next!!.hour)
        assertEquals(1, next.dayOfMonth)
    }

    @Test
    fun `parses empty string returns failure`() {
        val r = CronExpressionParser.parse("")
        assertTrue("empty string must return failure", r.isFailure)
    }

    @Test
    fun `rejects @every 0m, 0s, 0h`() {
        listOf("@every 0m", "@every 0s", "@every 0h").forEach {
            val r = CronExpressionParser.parse(it)
            assertTrue("$it should return failure", r.isFailure)
        }
    }

    @Test
    fun `nextExecution does not throw on never-firing expression`() {
        // Some impossible expressions parse but never fire (e.g. Feb 30).
        // We just want no exception leaking.
        val cron = CronExpressionParser.parse("0 0 30 2 *").getOrNull()
        if (cron != null) {
            val basis = ZonedDateTime.now(ZoneId.of("UTC"))
            // Either returns null or a far-future date; either is fine — no throw.
            CronExpressionParser.nextExecution(cron, basis)  // must not throw
        }
    }
}
