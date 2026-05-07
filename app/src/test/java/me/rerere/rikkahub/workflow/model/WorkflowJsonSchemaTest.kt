package me.rerere.rikkahub.workflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowJsonSchemaTest {

    private val knownTools = setOf("ssh_exec_saved", "telegram_send_message", "post_notification", "show_toast")

    // -- Reject paths (10+) --------------------------------------------------------------

    @Test fun `reject malformed JSON`() {
        val r = WorkflowJson.parse("{not json", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_json", r.error)
    }

    @Test fun `reject non-object root`() {
        val r = WorkflowJson.parse("[]", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("not_an_object", r.error)
    }

    @Test fun `reject missing name`() {
        val r = WorkflowJson.parse("""{"trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("missing_name", r.error)
    }

    @Test fun `reject blank name`() {
        val r = WorkflowJson.parse("""{"name":"","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_name", r.error)
    }

    @Test fun `reject overlong name`() {
        val long = "x".repeat(WorkflowConstants.MAX_NAME_LENGTH + 1)
        val r = WorkflowJson.parse("""{"name":"$long","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_name", r.error)
    }

    @Test fun `reject missing trigger`() {
        val r = WorkflowJson.parse("""{"name":"X","actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("missing_trigger", r.error)
    }

    @Test fun `reject unknown trigger type`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"phase_of_moon","params":{}},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("unknown_trigger_type", r.error)
    }

    @Test fun `reject empty actions`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"manual"},"actions":[]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("empty_actions", r.error)
    }

    @Test fun `reject unknown tool in actions`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"manual"},"actions":[{"tool":"format_disk","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("unknown_tool", r.error)
    }

    @Test fun `reject too many actions`() {
        val actions = buildString {
            append("[")
            for (i in 0 until WorkflowConstants.MAX_ACTIONS + 1) {
                if (i > 0) append(",")
                append("""{"tool":"show_toast","args":{}}""")
            }
            append("]")
        }
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"manual"},"actions":$actions}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("too_many_actions", r.error)
    }

    @Test fun `reject invalid timeout`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{},"timeout_seconds":99999}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_timeout", r.error)
    }

    @Test fun `reject battery_below threshold out of range`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"battery_below","params":{"threshold_percent":150}},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_trigger", r.error)
    }

    @Test fun `reject geofence radius out of range`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"geofence_enter","params":{"lat":0,"lng":0,"radius_m":10}},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_trigger", r.error)
    }

    @Test fun `reject notification_received with no filter`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"notification_received","params":{}},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_trigger", r.error)
    }

    @Test fun `reject time_cron with both cron and time_of_day`() {
        val r = WorkflowJson.parse("""{"name":"X","trigger":{"type":"time_cron","params":{"cron":"@every 30s","time_of_day":"09:00"}},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_trigger", r.error)
    }

    // -- Accept paths (5+) --------------------------------------------------------------

    @Test fun `accept manual trigger minimal definition`() {
        val r = WorkflowJson.parse("""{"name":"Hello","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi"}}]}""", knownTools)
        assertTrue(r is WorkflowJson.ParseResult.Ok)
    }

    @Test fun `accept wifi_connected with ssid filter`() {
        val r = WorkflowJson.parse("""{"name":"Home","trigger":{"type":"wifi_connected","params":{"ssid":"HomeWiFi"}},"actions":[{"tool":"show_toast","args":{"text":"home"}}]}""", knownTools)
        assertTrue(r is WorkflowJson.ParseResult.Ok)
    }

    @Test fun `accept time_cron with time_of_day plus days_of_week`() {
        val r = WorkflowJson.parse("""{"name":"Workday","trigger":{"type":"time_cron","params":{"time_of_day":"09:00","days_of_week":[1,2,3,4,5]}},"actions":[{"tool":"show_toast","args":{}}],"cooldown_seconds":60}""", knownTools)
        assertTrue(r is WorkflowJson.ParseResult.Ok)
    }

    @Test fun `accept geofence with conditions`() {
        val r = WorkflowJson.parse("""{
            "name":"Lights",
            "trigger":{"type":"geofence_enter","params":{"lat":40.7,"lng":-74.0,"radius_m":200,"label":"home"}},
            "conditions":[{"type":"time_after_sunset","params":{}}],
            "actions":[{"tool":"ssh_exec_saved","args":{"host_label":"home","command":"on"}}],
            "cooldown_seconds":300,
            "max_runs_per_day":10
        }""", knownTools)
        assertTrue(r is WorkflowJson.ParseResult.Ok)
    }

    @Test fun `accept notification_received with title filter`() {
        val r = WorkflowJson.parse("""{"name":"N","trigger":{"type":"notification_received","params":{"title_contains":"alarm"}},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools)
        assertTrue(r is WorkflowJson.ParseResult.Ok)
    }

    @Test fun `round-trip preserves trigger params`() {
        val raw = """{"name":"Z","trigger":{"type":"battery_below","params":{"threshold_percent":15}},"actions":[{"tool":"show_toast","args":{"text":"low"}}]}"""
        val parsed = (WorkflowJson.parse(raw, knownTools) as WorkflowJson.ParseResult.Ok).definition
        val encoded = WorkflowJson.encode(parsed)
        val reparsed = WorkflowJson.parseStored(encoded)
        assertNotNull(reparsed)
        val t = reparsed!!.trigger as TriggerSpec.BatteryBelow
        assertEquals(15, t.thresholdPercent)
    }

    @Test fun `id is generated when absent`() {
        val r = WorkflowJson.parse("""{"name":"Z","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{}}]}""", knownTools)
        val def = (r as WorkflowJson.ParseResult.Ok).definition
        assertTrue(def.id.isNotBlank())
    }

    @Test fun `parseStored skips tool-name validation`() {
        val raw = """{"id":"abc","name":"X","trigger":{"type":"manual"},"actions":[{"tool":"removed_tool","args":{}}]}"""
        val def = WorkflowJson.parseStored(raw)
        assertNotNull(def)
        assertEquals("removed_tool", def!!.actions[0].tool)
    }

    @Test fun `reject workflow_run as action tool prevents chaining`() {
        val withChain = setOf("show_toast", "workflow_run")
        val r = WorkflowJson.parse(
            """{"name":"Chained","trigger":{"type":"manual"},"actions":[{"tool":"workflow_run","args":{"id":"xyz"}}]}""",
            withChain,
        ) as WorkflowJson.ParseResult.Err
        assertEquals("workflow_chaining_disabled", r.error)
    }

    @Test fun `parseStored survives over-long values`() {
        val longName = "x".repeat(WorkflowConstants.MAX_NAME_LENGTH + 50)
        val raw = """{"id":"abc","name":"$longName","trigger":{"type":"manual"},"actions":[{"tool":"x","args":{}}]}"""
        // strict parse() would fail; parseStored() must still load the row so the user can
        // see + delete it. Schema-tightening should never silently drop existing rows.
        val def = WorkflowJson.parseStored(raw)
        assertNotNull("parseStored must NOT silently drop overlong-name stored rows", def)
        assertEquals(longName, def!!.name)
    }

    // ------- Trigger / condition param-shape leniency (regression) ------
    // The decoders historically required the canonical `{type, params:{...}}` envelope.
    // Most LLMs default to flat `{type, key1, key2}` and silently mis-authored every
    // workflow that needed required params. Both shapes must now parse.

    @Test fun `time_cron flat shape with time_of_day parses`() {
        val raw = """{"name":"daily","trigger":{"type":"time_cron","time_of_day":"09:00"},"actions":[{"tool":"show_toast","args":{}}]}"""
        val r = WorkflowJson.parse(raw, knownTools)
        assertTrue("flat trigger params must parse, got: $r", r is WorkflowJson.ParseResult.Ok)
    }

    @Test fun `time_cron nested params shape still parses`() {
        val raw = """{"name":"daily","trigger":{"type":"time_cron","params":{"time_of_day":"09:00"}},"actions":[{"tool":"show_toast","args":{}}]}"""
        val r = WorkflowJson.parse(raw, knownTools)
        assertTrue("nested trigger params must still parse, got: $r", r is WorkflowJson.ParseResult.Ok)
    }

    @Test fun `time_between condition flat shape parses`() {
        val raw = """
            {"name":"night","trigger":{"type":"manual"},
             "conditions":[{"type":"time_between","start":"23:00","end":"06:00"}],
             "actions":[{"tool":"show_toast","args":{}}]}
        """.trimIndent()
        val r = WorkflowJson.parse(raw, knownTools)
        assertTrue("flat condition params must parse (LLM default), got: $r", r is WorkflowJson.ParseResult.Ok)
        val def = (r as WorkflowJson.ParseResult.Ok).definition
        assertEquals(1, def.conditions.size)
    }

    @Test fun `time_between condition nested params shape still parses`() {
        val raw = """
            {"name":"night","trigger":{"type":"manual"},
             "conditions":[{"type":"time_between","params":{"start":"23:00","end":"06:00"}}],
             "actions":[{"tool":"show_toast","args":{}}]}
        """.trimIndent()
        val r = WorkflowJson.parse(raw, knownTools)
        assertTrue("nested condition params must still parse, got: $r", r is WorkflowJson.ParseResult.Ok)
    }
}
