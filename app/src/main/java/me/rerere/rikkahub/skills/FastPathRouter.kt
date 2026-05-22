package me.rerere.rikkahub.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Phase 16 — deterministic pre-LLM intent matcher.
 *
 * The router catches a small whitelist of high-confidence asks and executes them through
 * the existing tool registry, skipping the LLM entirely. The result is formatted by a
 * hand-written template per intent, then injected as a synthetic assistant message.
 *
 * Design constraints:
 *  - **Conservative.** When in doubt, fall through. False negatives are fine (LLM picks up);
 *    false positives are bad (wrong tool fires, user confused).
 *  - **Read-only tools only in v1.** Side-effecting tools (set_brightness, set_volume,
 *    launch_app) still go through the LLM so HARDLINE / approval surface as normal. Adding
 *    them to the router means designing the router's interaction with the approval flow,
 *    which is v1.5 work.
 *  - **English-only matchers in v1** — translation pass is deferred per CLAUDE.md.
 *  - **HARDLINE / approval still apply** if the matcher dispatches a side-effecting tool
 *    in a future release. The router is an LLM-skip optimisation, not a security bypass.
 *
 * The router is opt-in per assistant (`Assistant.fastPathRouterEnabled`, default false)
 * and audit-logged so the user can see which intents fired in
 * `Settings → Assistants → (assistant) → Fast-path router → Recent matches`.
 */
object FastPathRouter {

    /**
     * A successful match. The router asks ChatService to execute [toolName] with [args],
     * then format the result via [format] (or pass it straight if format is null).
     */
    data class Match(
        val intent: String,                // stable id for logging — "battery", "time", etc.
        val toolName: String,
        val args: JsonObject,
        val format: ((JsonObject) -> String)? = null,
    )

    /**
     * Try to match a user's free-text [message] against the registered intents.
     * Returns null if nothing matches at high confidence.
     *
     * The order of [Intents] is fixed — earlier intents win on ambiguous matches. Keep
     * the most-specific patterns first.
     */
    fun route(message: String): Match? {
        val normalized = message.trim().lowercase().removeSuffix("?").trim()
        for (intent in Intents) {
            val match = intent.tryMatch(normalized) ?: continue
            return match
        }
        return null
    }

    /** Each intent is a triple: id + matcher + producer. */
    interface Intent {
        fun tryMatch(normalized: String): Match?
    }

    private val Intents: List<Intent> = listOf(
        // -- Battery ----------------------------------------------------------------------
        object : Intent {
            private val pat = Regex("""^(what(?:'s|\s+is)?\s+(?:my\s+|the\s+)?battery(?:\s+(?:level|percent|status|life))?|battery\??)$""")
            override fun tryMatch(normalized: String): Match? {
                if (!pat.matches(normalized)) return null
                return Match(
                    intent = "battery",
                    toolName = "get_battery_status",
                    args = buildJsonObject { },
                    format = { result ->
                        // get_battery_status emits "percent" + "charging" — see BatteryTool.kt.
                        val pct = result["percent"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val charging = result["charging"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                        if (charging) "Battery is at $pct% and charging."
                        else "Battery is at $pct%."
                    },
                )
            }
        },
        // -- Time / date -----------------------------------------------------------------
        object : Intent {
            private val pat = Regex("""^(what(?:'s|\s+is)?\s+the\s+time|what\s+time\s+is\s+it|time\??)$""")
            override fun tryMatch(normalized: String): Match? {
                if (!pat.matches(normalized)) return null
                return Match(
                    intent = "time",
                    toolName = "get_time_info",
                    args = buildJsonObject { },
                    format = { result ->
                        val t = result["time"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val d = result["date"]?.jsonPrimitive?.contentOrNull ?: ""
                        val wd = result["weekday"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (d.isNotBlank() && wd.isNotBlank()) "It's $t on $wd, $d."
                        else "It's $t."
                    },
                )
            }
        },
        object : Intent {
            private val pat = Regex("""^(what(?:'s|\s+is)?\s+(?:the\s+|today's\s+)?date|what\s+day\s+(?:is\s+it|is\s+today)|today's\s+date|date\??)$""")
            override fun tryMatch(normalized: String): Match? {
                if (!pat.matches(normalized)) return null
                return Match(
                    intent = "date",
                    toolName = "get_time_info",
                    args = buildJsonObject { },
                    format = { result ->
                        val d = result["date"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val wd = result["weekday"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (wd.isNotBlank()) "Today is $wd, $d." else "Today is $d."
                    },
                )
            }
        },
        // -- Storage ---------------------------------------------------------------------
        object : Intent {
            private val pat = Regex("""^(what(?:'s|\s+is)?\s+(?:my\s+|the\s+)?(?:free\s+)?storage(?:\s+(?:left|space))?|storage\s+left|how\s+much\s+(?:storage|space)\s+(?:do\s+i\s+have\s+)?(?:left|free)?|storage\??)$""")
            override fun tryMatch(normalized: String): Match? {
                if (!pat.matches(normalized)) return null
                return Match(
                    intent = "storage",
                    toolName = "get_storage_info",
                    args = buildJsonObject { },
                    format = { result ->
                        // get_storage_info nests under "internal": { total_bytes, free_bytes }.
                        val internal = result["internal"] as? JsonObject
                        val freeBytes = internal?.get("free_bytes")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        val totalBytes = internal?.get("total_bytes")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        val gb = 1024.0 * 1024 * 1024
                        val freeGb = freeBytes?.let { it / gb } ?: 0.0
                        val totalGb = totalBytes?.let { it / gb } ?: 0.0
                        when {
                            freeBytes == null -> "Storage info unavailable."
                            totalGb > 0.0 -> "%.1f GB free of %.1f GB.".format(freeGb, totalGb)
                            else -> "%.1f GB free.".format(freeGb)
                        }
                    },
                )
            }
        },
        // -- Wifi info ------------------------------------------------------------------
        object : Intent {
            private val pat = Regex("""^(what(?:'s|\s+is)?\s+(?:my\s+)?wi-?fi(?:\s+(?:network|status))?|wifi(?:\s+status)?\??|am\s+i\s+(?:on|connected\s+to)\s+wi-?fi)$""")
            override fun tryMatch(normalized: String): Match? {
                if (!pat.matches(normalized)) return null
                return Match(
                    intent = "wifi",
                    toolName = "get_wifi_info",
                    args = buildJsonObject { },
                    format = { result ->
                        // get_wifi_info emits "connected" + (when connected) "ssid".
                        // The "error" branch (no permission / no service) returns just an error key.
                        val err = result["error"]?.jsonPrimitive?.contentOrNull
                        val ssid = result["ssid"]?.jsonPrimitive?.contentOrNull
                        val connected = result["connected"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                        when {
                            err != null -> "WiFi info unavailable: $err."
                            !connected -> "WiFi is off or not connected."
                            !ssid.isNullOrBlank() && ssid != "<unknown ssid>" -> "Connected to $ssid."
                            else -> "Connected to WiFi (SSID hidden)."
                        }
                    },
                )
            }
        },
        // -- List workflows -------------------------------------------------------------
        object : Intent {
            private val pat = Regex("""^(list\s+(?:my\s+)?workflows|what\s+workflows\s+(?:do\s+i\s+have|are\s+(?:running|enabled))|show\s+(?:me\s+)?(?:my\s+)?workflows)$""")
            override fun tryMatch(normalized: String): Match? {
                if (!pat.matches(normalized)) return null
                return Match(
                    intent = "list_workflows",
                    toolName = "workflow_list",
                    args = buildJsonObject { },
                    format = { result ->
                        val items = result["items"]
                        val count = result["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        if (count == 0) "No workflows configured."
                        else {
                            val arr = (items as? kotlinx.serialization.json.JsonArray)
                            val names = arr?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.orEmpty()
                            if (names.isEmpty()) "$count workflow(s) configured."
                            else "$count workflow(s): " + names.joinToString(", ") + "."
                        }
                    },
                )
            }
        },
        // -- List scheduled jobs --------------------------------------------------------
        object : Intent {
            private val pat = Regex("""^(list\s+(?:my\s+)?(?:scheduled\s+)?jobs|what\s+jobs\s+(?:do\s+i\s+have|are\s+(?:running|scheduled))|show\s+(?:me\s+)?(?:my\s+)?(?:scheduled\s+)?jobs)$""")
            override fun tryMatch(normalized: String): Match? {
                if (!pat.matches(normalized)) return null
                return Match(
                    intent = "list_jobs",
                    toolName = "list_jobs",
                    args = buildJsonObject { },
                    format = null,  // list_jobs already returns a readable text envelope
                )
            }
        },
    )
}
