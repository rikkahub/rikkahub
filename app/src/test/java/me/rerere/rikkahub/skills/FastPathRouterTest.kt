package me.rerere.rikkahub.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathRouterTest {

    @Test fun `battery intent matches`() {
        for (variant in listOf(
            "what's my battery", "what is my battery", "what's the battery level",
            "battery", "battery?", "BATTERY?",
            "what's my battery percent", "what's my battery status",
        )) {
            val m = FastPathRouter.route(variant)
            assertNotNull("expected match for '$variant'", m)
            assertEquals("battery", m!!.intent)
            assertEquals("get_battery_status", m.toolName)
        }
    }

    @Test fun `time intent matches`() {
        for (variant in listOf("what time is it", "what's the time", "time", "time?")) {
            val m = FastPathRouter.route(variant)
            assertNotNull("expected match for '$variant'", m)
            assertEquals("time", m!!.intent)
        }
    }

    @Test fun `date intent matches`() {
        for (variant in listOf(
            "what's the date", "what is the date", "what day is it",
            "what day is today", "today's date", "date",
        )) {
            val m = FastPathRouter.route(variant)
            assertNotNull("expected match for '$variant'", m)
            assertEquals("date", m!!.intent)
        }
    }

    @Test fun `storage intent matches`() {
        for (variant in listOf(
            "storage", "storage?", "what's my storage",
            "what's my free storage", "what's the storage left",
            "how much storage do i have left", "storage left",
        )) {
            val m = FastPathRouter.route(variant)
            assertNotNull("expected match for '$variant'", m)
            assertEquals("storage", m!!.intent)
        }
    }

    @Test fun `wifi intent matches`() {
        for (variant in listOf(
            "what's my wifi", "what wifi", "wifi", "wifi?",
            "wifi status", "am i on wifi", "am i connected to wifi",
        )) {
            val m = FastPathRouter.route(variant)
            assertNotNull("expected match for '$variant'", m)
            assertEquals("wifi", m!!.intent)
        }
    }

    @Test fun `list workflows intent matches`() {
        for (variant in listOf(
            "list my workflows", "list workflows", "show me workflows",
            "what workflows do i have", "show my workflows",
        )) {
            val m = FastPathRouter.route(variant)
            assertNotNull("expected match for '$variant'", m)
            assertEquals("list_workflows", m!!.intent)
            assertEquals("workflow_list", m.toolName)
        }
    }

    @Test fun `list jobs intent matches`() {
        for (variant in listOf(
            "list my jobs", "list jobs", "show my scheduled jobs",
            "what jobs are scheduled", "show me jobs",
        )) {
            val m = FastPathRouter.route(variant)
            assertNotNull("expected match for '$variant'", m)
            assertEquals("list_jobs", m!!.intent)
        }
    }

    // -- Fall-through cases ----------------------------------------------------------

    @Test fun `ambiguous prompts fall through`() {
        for (text in listOf(
            "what about my battery though",
            "tell me a joke about battery",
            "the battery is dead, can you fix it?",
            "set my battery to 100%",
            "translate 'what time is it' to spanish",
            "i need help with the date format in this code",
            "what's the weather",
            "play some music",
            "open instagram",
            "call mom",
            "set a timer for 5 minutes",
        )) {
            assertNull("expected NO match for '$text', got ${FastPathRouter.route(text)}",
                FastPathRouter.route(text))
        }
    }

    @Test fun `case insensitive`() {
        assertNotNull(FastPathRouter.route("WHAT TIME IS IT"))
        assertNotNull(FastPathRouter.route("BATTERY"))
        assertNotNull(FastPathRouter.route("StOrAgE"))
    }

    @Test fun `trailing punctuation tolerated`() {
        assertNotNull(FastPathRouter.route("battery?"))
        assertNotNull(FastPathRouter.route("what's the time?"))
    }

    @Test fun `empty and blank fall through`() {
        assertNull(FastPathRouter.route(""))
        assertNull(FastPathRouter.route("   "))
    }

    // -- Format closures: assert each intent reads the keys its tool actually emits ----
    // Regression guard for an earlier bug where the router read level_percent / is_charging
    // / internal_free_mb / is_connected, but the tools emit percent / charging / nested
    // internal.{free,total}_bytes / connected. When a key doesn't match the closure renders
    // "?" or zero — the user sees "Battery is at ?%." in chat.

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    @Test fun `battery format substitutes the percent and charging keys actually emitted`() {
        val m = FastPathRouter.route("battery")!!
        // BatteryTool.kt emits {"percent": 67, "charging": true, ...}
        val out = m.format!!(parse("""{"percent":67,"charging":true,"plugged":"ac","health":"good"}"""))
        assertEquals("Battery is at 67% and charging.", out)

        val notCharging = m.format!!(parse("""{"percent":42,"charging":false,"plugged":"none","health":"good"}"""))
        assertEquals("Battery is at 42%.", notCharging)
    }

    @Test fun `storage format reads nested internal free_bytes and total_bytes`() {
        val m = FastPathRouter.route("storage")!!
        // StorageTool.kt nests bytes under "internal".
        val raw = """{"internal":{"total_bytes":${64L * 1024 * 1024 * 1024},"free_bytes":${16L * 1024 * 1024 * 1024},"used_bytes":${48L * 1024 * 1024 * 1024}},"external":null}"""
        val out = m.format!!(parse(raw))
        // 16 GB free of 64 GB — should render with one decimal.
        assertTrue("expected '16.0 GB free of 64.0 GB.', got '$out'", out.contains("16.0 GB free") && out.contains("64.0 GB"))
    }

    @Test fun `wifi format reads connected and ssid keys actually emitted`() {
        val m = FastPathRouter.route("wifi")!!
        // WifiInfoTool.kt emits {"connected": true, "ssid": "...", ...}
        val online = m.format!!(parse("""{"connected":true,"ssid":"HomeNet","bssid":"aa:bb","ip":"10.0.0.1"}"""))
        assertEquals("Connected to HomeNet.", online)

        val offline = m.format!!(parse("""{"connected":false}"""))
        assertEquals("WiFi is off or not connected.", offline)
    }
}
