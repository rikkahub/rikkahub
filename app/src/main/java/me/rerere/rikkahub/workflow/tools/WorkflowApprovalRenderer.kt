package me.rerere.rikkahub.workflow.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.workflow.model.ConditionSpec
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson

/**
 * Phase 12 — human-readable approval prompt renderer for `workflow_create` /
 * `workflow_update` (in-app dialog + Telegram approval keyboard message).
 *
 * Output template (matches spec §"Approval prompt rendering"):
 * ```
 * Create workflow "<name>"
 *
 * When: <trigger summary>
 * Only if: <comma-separated condition summaries, or "always">
 * Do:
 *   1. <action 1 summary>
 *   2. <action 2 summary>
 *   ...
 *
 * Cooldown: <X seconds, or "none">
 * Daily cap: <N runs, or "unlimited">
 * ```
 *
 * Telegram variant uses HTML — `<b>` for the title, `<code>` for tool names. Same content,
 * just lightly marked up.
 */
object WorkflowApprovalRenderer {

    /** True if the tool is one of ours and should use this renderer (not the JSON dump). */
    fun isWorkflowTool(toolName: String): Boolean = toolName in WORKFLOW_TOOL_NAMES

    /** Plain-text rendering for the in-app approval card. */
    fun renderPlain(toolName: String, argsJson: String): String {
        val verb = when (toolName) {
            "workflow_create" -> "Create"
            "workflow_update" -> "Update"
            "workflow_delete" -> return renderDelete(argsJson, html = false)
            "workflow_set_enabled" -> return renderSetEnabled(argsJson, html = false)
            "workflow_run" -> return renderRunNow(argsJson, html = false)
            else -> return argsJson  // unknown — fall through to default
        }
        val def = parseDefinition(argsJson) ?: return argsJson
        val sb = StringBuilder()
        sb.appendLine("$verb workflow \"${def.name}\"")
        sb.appendLine()
        sb.appendLine("When: ${triggerSummary(def.trigger)}")
        sb.appendLine("Only if: ${
            if (def.conditions.isEmpty()) "always"
            else def.conditions.joinToString(", ") { conditionSummary(it) }
        }")
        sb.appendLine("Do:")
        for ((idx, action) in def.actions.withIndex()) {
            sb.appendLine("  ${idx + 1}. ${actionSummary(action)}")
        }
        sb.appendLine()
        sb.appendLine("Cooldown: ${
            if (def.cooldownSeconds == 0) "none"
            else "${def.cooldownSeconds}s"
        }")
        sb.appendLine("Daily cap: ${def.maxRunsPerDay?.let { "$it runs" } ?: "unlimited"}")
        return sb.toString().trimEnd()
    }

    /** Telegram HTML rendering — same content, with `<b>` and `<code>` markup. */
    fun renderTelegramHtml(toolName: String, argsJson: String): String {
        val verb = when (toolName) {
            "workflow_create" -> "Create"
            "workflow_update" -> "Update"
            "workflow_delete" -> return renderDelete(argsJson, html = true)
            "workflow_set_enabled" -> return renderSetEnabled(argsJson, html = true)
            "workflow_run" -> return renderRunNow(argsJson, html = true)
            else -> return escapeHtml(argsJson)
        }
        val def = parseDefinition(argsJson) ?: return escapeHtml(argsJson)
        val sb = StringBuilder()
        sb.appendLine("<b>$verb workflow \"${escapeHtml(def.name)}\"</b>")
        sb.appendLine()
        sb.appendLine("<b>When:</b> ${escapeHtml(triggerSummary(def.trigger))}")
        sb.appendLine("<b>Only if:</b> ${
            if (def.conditions.isEmpty()) "always"
            else def.conditions.joinToString(", ") { escapeHtml(conditionSummary(it)) }
        }")
        sb.appendLine("<b>Do:</b>")
        for ((idx, action) in def.actions.withIndex()) {
            sb.appendLine("  ${idx + 1}. <code>${escapeHtml(action.tool)}</code>(${escapeHtml(actionArgsHint(action))})")
        }
        sb.appendLine()
        sb.appendLine("<b>Cooldown:</b> ${
            if (def.cooldownSeconds == 0) "none"
            else "${def.cooldownSeconds}s"
        }")
        sb.appendLine("<b>Daily cap:</b> ${def.maxRunsPerDay?.let { "$it runs" } ?: "unlimited"}")
        return sb.toString().trimEnd()
    }

    private fun parseDefinition(argsJson: String): WorkflowDefinition? {
        val obj = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return null
        val defStr = (obj["definition"] as? JsonObject)?.toString() ?: argsJson
        return WorkflowJson.parseStored(defStr)
    }

    private fun triggerSummary(t: TriggerSpec): String = when (t) {
        is TriggerSpec.TimeCron -> when {
            !t.timeOfDay.isNullOrBlank() -> {
                val days = if (t.daysOfWeek.isEmpty()) "every day" else "on day(s) ${t.daysOfWeek.joinToString(",")}"
                "every ${t.timeOfDay} $days"
            }
            !t.cron.isNullOrBlank() -> "schedule (${t.cron})"
            else -> "schedule"
        }
        is TriggerSpec.WifiConnected -> "WiFi connects" + (t.ssid?.let { " to $it" }.orEmpty())
        is TriggerSpec.WifiDisconnected -> "WiFi disconnects" + (t.ssid?.let { " from $it" }.orEmpty())
        is TriggerSpec.BluetoothDeviceConnected -> "Bluetooth device connects" + (t.deviceAddress?.let { " ($it)" }.orEmpty())
        is TriggerSpec.BluetoothDeviceDisconnected -> "Bluetooth device disconnects" + (t.deviceAddress?.let { " ($it)" }.orEmpty())
        is TriggerSpec.HeadphonesPlugged -> "headphones plugged in"
        is TriggerSpec.HeadphonesUnplugged -> "headphones unplugged"
        is TriggerSpec.PowerConnected -> "power connected"
        is TriggerSpec.PowerDisconnected -> "power disconnected"
        is TriggerSpec.BatteryBelow -> "battery drops below ${t.thresholdPercent}%"
        is TriggerSpec.BatteryAbove -> "battery rises above ${t.thresholdPercent}%"
        is TriggerSpec.GeofenceEnter -> "you enter ${t.label ?: "a place (${t.lat},${t.lng}, ${t.radiusM}m)"}"
        is TriggerSpec.GeofenceExit -> "you leave ${t.label ?: "a place (${t.lat},${t.lng}, ${t.radiusM}m)"}"
        is TriggerSpec.AppLaunched -> "${t.packageName} is launched"
        is TriggerSpec.AppClosed -> "${t.packageName} is closed"
        is TriggerSpec.NotificationReceived -> {
            val parts = mutableListOf<String>()
            t.packageName?.let { parts += "from $it" }
            t.titleContains?.let { parts += "title contains '$it'" }
            t.textContains?.let { parts += "text contains '$it'" }
            "a notification arrives" + if (parts.isEmpty()) "" else " (${parts.joinToString("; ")})"
        }
        is TriggerSpec.BootCompleted -> "device boots"
        is TriggerSpec.ScreenOn -> "screen turns on"
        is TriggerSpec.ScreenOff -> "screen turns off"
        is TriggerSpec.Manual -> "you fire it manually (Run now)"
    }

    private fun conditionSummary(c: ConditionSpec): String = when (c) {
        is ConditionSpec.TimeBetween -> "between ${c.start} and ${c.end}"
        is ConditionSpec.TimeAfterSunset -> "after sunset" + if (c.offsetMinutes != 0) " (${c.offsetMinutes}m offset)" else ""
        is ConditionSpec.TimeBeforeSunrise -> "before sunrise" + if (c.offsetMinutes != 0) " (${c.offsetMinutes}m offset)" else ""
        is ConditionSpec.DayOfWeekIn -> "on day(s) ${c.days.joinToString(",")}"
        is ConditionSpec.WifiSsidIs -> "WiFi is ${c.ssid}"
        is ConditionSpec.WifiSsidIn -> "WiFi in ${c.ssids.joinToString(",")}"
        is ConditionSpec.BatteryAbove -> "battery > ${c.percent}%"
        is ConditionSpec.BatteryBelow -> "battery < ${c.percent}%"
        is ConditionSpec.IsCharging -> "charging"
        is ConditionSpec.IsNotCharging -> "not charging"
        is ConditionSpec.ForegroundAppIs -> "${c.packageName} is foreground"
        is ConditionSpec.ForegroundAppIn -> "${c.packageNames.size}-app foreground match"
        is ConditionSpec.ScreenIsOn -> "screen on"
        is ConditionSpec.ScreenIsOff -> "screen off"
    }

    private fun actionSummary(action: WorkflowAction): String {
        val hint = actionArgsHint(action)
        return "${action.tool}($hint)"
    }

    /**
     * Truncate args at ~80 chars per spec. Best-effort `key=value` rendering. Values whose
     * key matches the secret-redaction list (token, password, private_key, etc.) are masked
     * with `***` regardless of length — otherwise an LLM-authored workflow whose action is
     * `telegram_set_token({token: "..."})` or `save_ssh_host({password: "..."})` would echo
     * the secret into the user's chat history (in-app card AND Telegram approval message).
     */
    private fun actionArgsHint(action: WorkflowAction): String {
        if (action.args.isEmpty()) return ""
        val pairs = action.args.entries.joinToString(", ") { (k, v) ->
            val str = if (isSensitiveKey(k)) {
                "***"
            } else when (v) {
                is JsonPrimitive -> v.contentOrNull?.take(40).orEmpty()
                else -> v.toString().take(40)
            }
            "$k=\"$str\""
        }
        return pairs.take(80) + if (pairs.length > 80) "…" else ""
    }

    /**
     * Match a key name against the redaction list (case-insensitive, snake_case AND
     * camelCase aware). Mirrors the redaction model already used by McpApprovalRenderer
     * for header values.
     */
    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase().replace("-", "_").replace(" ", "_")
        return SENSITIVE_KEY_PARTS.any { part -> lower == part || lower.contains(part) }
    }

    private val SENSITIVE_KEY_PARTS = setOf(
        "token", "password", "passphrase", "private_key", "privatekey",
        "secret", "api_key", "apikey", "authorization", "auth_token", "access_token",
        "client_secret", "credential", "credentials",
    )

    private fun renderDelete(argsJson: String, html: Boolean): String {
        val id = runCatching {
            (Json.parseToJsonElement(argsJson) as? JsonObject)
                ?.get("id")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: "?"
        val msg = "Delete workflow id=$id"
        return if (html) "<b>${escapeHtml(msg)}</b>" else msg
    }

    private fun renderSetEnabled(argsJson: String, html: Boolean): String {
        val obj = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
        val id = obj?.get("id")?.jsonPrimitive?.contentOrNull ?: "?"
        val enabled = obj?.get("enabled")?.jsonPrimitive?.contentOrNull
        val verb = if (enabled == "true") "Enable" else "Disable"
        val msg = "$verb workflow id=$id"
        return if (html) "<b>${escapeHtml(msg)}</b>" else msg
    }

    private fun renderRunNow(argsJson: String, html: Boolean): String {
        val id = runCatching {
            (Json.parseToJsonElement(argsJson) as? JsonObject)
                ?.get("id")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: "?"
        val msg = "Run workflow now id=$id"
        return if (html) "<b>${escapeHtml(msg)}</b>" else msg
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    val WORKFLOW_TOOL_NAMES = setOf(
        "workflow_create",
        "workflow_list",
        "workflow_get",
        "workflow_update",
        "workflow_delete",
        "workflow_set_enabled",
        "workflow_run",
    )
}
