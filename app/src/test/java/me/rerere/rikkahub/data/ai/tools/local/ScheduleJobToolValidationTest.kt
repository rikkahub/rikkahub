package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleJobToolValidationTest {

    private val knownTools = listOf("post_notification", "telegram_send_message", "termux_run_command")

    private fun base(): JsonObject = buildJsonObject {
        put("name", "test")
        put("mode", "llm")
        put("schedule_type", "cron")
        put("cron_expression", "@hourly")
        put("prompt", "do a thing")
    }

    @Test
    fun `valid llm cron job passes`() {
        val r = ScheduleJobValidator.validate(base(), knownTools)
        assertNull(r)
    }

    @Test
    fun `invalid cron returns invalid_cron`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "test")
            put("mode", "llm")
            put("schedule_type", "cron")
            put("cron_expression", "not a cron")
            put("prompt", "x")
        }, knownTools)
        assertNotNull(r)
        assertEquals("invalid_cron", r!!.code)
    }

    @Test
    fun `mode llm without prompt rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("mutual_exclusive", r!!.code)
    }

    @Test
    fun `mode direct empty actions rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("empty_actions", r!!.code)
    }

    @Test
    fun `mode direct unknown tool rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "no_such_tool"); put("args", buildJsonObject { })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("unknown_tool", r!!.code)
    }

    @Test
    fun `mode direct hardline-blocked rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("actions", buildJsonArray { add(buildJsonObject {
                put("tool", "termux_run_command")
                put("args", buildJsonObject { put("command", "rm -rf /") })
            }) })
            put("schedule_type", "once"); put("at_unix_ms", 100L)
        }, knownTools)
        assertEquals("hardline_blocked", r!!.code)
    }

    @Test
    fun `bounds inverted rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("start_at_unix_ms", 200L); put("end_at_unix_ms", 100L)
        }, knownTools)
        assertEquals("bounds_inverted", r!!.code)
    }

    @Test
    fun `bad timezone rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("timezone", "Mars/Olympus")
        }, knownTools)
        assertEquals("bad_timezone", r!!.code)
    }

    @Test
    fun `too many actions rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "direct")
            put("schedule_type", "once"); put("at_unix_ms", 100L)
            put("actions", buildJsonArray {
                repeat(51) {
                    add(buildJsonObject {
                        put("tool", "post_notification")
                        put("args", buildJsonObject { put("title", "t"); put("body", "b") })
                    })
                }
            })
        }, knownTools)
        assertEquals("too_many_actions", r!!.code)
    }

    @Test
    fun `prompt too long rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "a".repeat(4001))
        }, knownTools)
        assertEquals("prompt_too_long", r!!.code)
    }

    @Test
    fun `prompt at limit accepted`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("prompt", "a".repeat(4000))
        }, knownTools)
        assertNull(r)
    }

    @Test
    fun `bounds past rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("end_at_unix_ms", 1L)  // epoch ms — always in the past
        }, knownTools)
        assertEquals("bounds_past", r!!.code)
    }

    @Test
    fun `max_runs zero rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("max_runs", 0)
        }, knownTools)
        assertEquals("max_runs_invalid", r!!.code)
    }

    @Test
    fun `bad catchup value rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("catchup", "never")
        }, knownTools)
        assertEquals("bad_catchup", r!!.code)
    }

    @Test
    fun `bad tag uppercase rejected`() {
        val r = ScheduleJobValidator.validate(buildJsonObject {
            put("name", "x"); put("mode", "llm"); put("prompt", "p")
            put("schedule_type", "cron"); put("cron_expression", "@hourly")
            put("tags", buildJsonArray { add("HasUppercase") })
        }, knownTools)
        assertEquals("bad_tag", r!!.code)
    }
}
