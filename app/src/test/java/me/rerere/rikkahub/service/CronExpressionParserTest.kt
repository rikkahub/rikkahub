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
