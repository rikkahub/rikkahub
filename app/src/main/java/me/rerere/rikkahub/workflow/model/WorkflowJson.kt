package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * Phase 12 — strict JSON schema validator + parser/serializer.
 *
 * Wire shape (LLM-facing):
 * ```
 * { "trigger": { "type": "wifi_connected", "params": { "ssid": "X" } },
 *   "conditions": [ { "type": "time_after_sunset", "params": { } } ],
 *   "actions": [ { "tool": "...", "args": { ... } } ] }
 * ```
 *
 * kotlinx polymorphic-sealed default is `{ "type": "...", <inline-fields> }`. We bridge by
 * flattening the `params` object into the same JSON object as `type` before calling the
 * polymorphic decoder. That way the LLM sees a tidy nested schema while we get static
 * typing on the Kotlin side. Errors return [ParseResult.Err] with stable codes the tools
 * pass straight back as their error envelopes.
 */
object WorkflowJson {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val strict: Json = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
        explicitNulls = false
        encodeDefaults = true
        // The wire shape uses snake_case (LLM-facing); Kotlin sealed-class fields are
        // camelCase. The naming strategy is applied to all properties of @Serializable
        // classes — both encode and decode — so `thresholdPercent` becomes
        // `threshold_percent` in JSON.
        namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
    }

    sealed class ParseResult {
        data class Ok(val definition: WorkflowDefinition) : ParseResult()
        data class Err(val error: String, val detail: String) : ParseResult() {
            fun withIndex(idx: Int, kind: String): Err = Err(error, "$kind[$idx]: $detail")
        }
    }

    /**
     * Parse a workflow definition from the LLM's JSON. Returns Err for any structural problem
     * with a stable error code. The caller is expected to surface the error verbatim so the
     * LLM can repair its emission.
     */
    fun parse(rawJson: String, knownToolNames: Set<String>): ParseResult {
        val element: JsonElement = runCatching { Json.parseToJsonElement(rawJson) }.getOrElse {
            return ParseResult.Err("invalid_json", it.message ?: "JSON parse failed")
        }
        val obj = element as? JsonObject
            ?: return ParseResult.Err("not_an_object", "definition must be a JSON object")

        val name = obj["name"]?.jsonPrimitive?.contentOrNull
            ?: return ParseResult.Err("missing_name", "name is required")
        if (name.isBlank()) return ParseResult.Err("invalid_name", "name must be non-blank")
        if (name.length > WorkflowConstants.MAX_NAME_LENGTH) {
            return ParseResult.Err("invalid_name",
                "name must be ≤ ${WorkflowConstants.MAX_NAME_LENGTH} chars")
        }

        val description = obj["description"]?.jsonPrimitive?.contentOrNull
            ?.take(WorkflowConstants.MAX_DESCRIPTION_LENGTH)
        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true

        val triggerObj = obj["trigger"] as? JsonObject
            ?: return ParseResult.Err("missing_trigger", "trigger object is required")
        val trigger = when (val r = decodeTrigger(triggerObj)) {
            is DecodeOk -> r.value as TriggerSpec
            is DecodeErr -> return r.err
        }

        val conditionsArr = obj["conditions"]?.jsonArray ?: buildJsonArray { }
        val conditions = mutableListOf<ConditionSpec>()
        for ((idx, el) in conditionsArr.withIndex()) {
            val condObj = el as? JsonObject
                ?: return ParseResult.Err("bad_condition_shape", "condition $idx is not an object")
            val cond = when (val r = decodeCondition(condObj)) {
                is DecodeOk -> r.value as ConditionSpec
                is DecodeErr -> return r.err.withIndex(idx, "condition")
            }
            conditions += cond
        }

        val actionsArr = obj["actions"]?.jsonArray
            ?: return ParseResult.Err("missing_actions", "actions array is required")
        if (actionsArr.isEmpty()) {
            return ParseResult.Err("empty_actions", "actions must be non-empty")
        }
        if (actionsArr.size > WorkflowConstants.MAX_ACTIONS) {
            return ParseResult.Err("too_many_actions",
                "actions must be ≤ ${WorkflowConstants.MAX_ACTIONS}")
        }
        val actions = mutableListOf<WorkflowAction>()
        for ((idx, el) in actionsArr.withIndex()) {
            val ao = el as? JsonObject
                ?: return ParseResult.Err("bad_action_shape", "action $idx is not an object")
            val toolName = ao["tool"]?.jsonPrimitive?.contentOrNull
                ?: return ParseResult.Err("missing_tool", "action $idx missing 'tool'")
            // knownToolNames is the assistant's currently-registered tool surface. Empty set is
            // a sentinel meaning "skip the check" — used when reading stored definitions back
            // from disk where we trust that what was persisted was already validated.
            if (knownToolNames.isNotEmpty() && toolName !in knownToolNames) {
                return ParseResult.Err("unknown_tool",
                    "action $idx tool '$toolName' is not registered for this assistant")
            }
            // Forbid workflow chaining via workflow_run as an action. The spec explicitly
            // lists "Workflow chaining (one workflow triggering another)" as out-of-scope
            // for v1 — without this guard, a malicious or hallucinated workflow definition
            // could trigger an unbounded chain across distinct workflow ids that the
            // per-workflow Mutex doesn't catch.
            if (toolName == "workflow_run") {
                return ParseResult.Err("workflow_chaining_disabled",
                    "action $idx: workflow_run cannot be used as a workflow action (chaining is out-of-scope in v1)")
            }
            val args = ao["args"] as? JsonObject ?: buildJsonObject { }
            val timeout = ao["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60
            if (timeout < WorkflowConstants.MIN_ACTION_TIMEOUT_S
                || timeout > WorkflowConstants.MAX_ACTION_TIMEOUT_S) {
                return ParseResult.Err("invalid_timeout",
                    "action $idx timeout_seconds must be ${WorkflowConstants.MIN_ACTION_TIMEOUT_S}..${WorkflowConstants.MAX_ACTION_TIMEOUT_S}")
            }
            actions += WorkflowAction(tool = toolName, args = args, timeoutSeconds = timeout)
        }

        val cooldown = obj["cooldown_seconds"]?.jsonPrimitive?.intOrNull ?: 0
        if (cooldown < 0 || cooldown > WorkflowConstants.MAX_COOLDOWN_S) {
            return ParseResult.Err("invalid_cooldown",
                "cooldown_seconds must be 0..${WorkflowConstants.MAX_COOLDOWN_S}")
        }

        val maxRunsPerDay = obj["max_runs_per_day"]?.jsonPrimitive?.intOrNull
        if (maxRunsPerDay != null && (maxRunsPerDay < WorkflowConstants.MAX_RUNS_PER_DAY_FLOOR
                    || maxRunsPerDay > WorkflowConstants.MAX_RUNS_PER_DAY_CEIL)) {
            return ParseResult.Err("invalid_daily_cap",
                "max_runs_per_day must be ${WorkflowConstants.MAX_RUNS_PER_DAY_FLOOR}..${WorkflowConstants.MAX_RUNS_PER_DAY_CEIL}")
        }

        sanityCheckTrigger(trigger)?.let { return it }
        for ((idx, c) in conditions.withIndex()) {
            sanityCheckCondition(c)?.let { return it.withIndex(idx, "condition") }
        }

        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: kotlin.uuid.Uuid.random().toString()

        val now = System.currentTimeMillis()
        return ParseResult.Ok(WorkflowDefinition(
            id = id,
            name = name.trim(),
            description = description?.trim(),
            enabled = enabled,
            trigger = trigger,
            conditions = conditions,
            actions = actions,
            cooldownSeconds = cooldown,
            maxRunsPerDay = maxRunsPerDay,
            createdAtMs = obj["created_at_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now,
            updatedAtMs = now,
        ))
    }

    /** Serialize a definition back to canonical wire JSON. */
    fun encode(definition: WorkflowDefinition): String {
        val obj = buildJsonObject {
            put("id", JsonPrimitive(definition.id))
            put("name", JsonPrimitive(definition.name))
            if (definition.description != null) put("description", JsonPrimitive(definition.description))
            put("enabled", JsonPrimitive(definition.enabled))
            put("trigger", encodeTrigger(definition.trigger))
            put("conditions", buildJsonArray {
                for (c in definition.conditions) add(encodeCondition(c))
            })
            put("actions", buildJsonArray {
                for (a in definition.actions) {
                    add(buildJsonObject {
                        put("tool", JsonPrimitive(a.tool))
                        put("args", a.args)
                        put("timeout_seconds", JsonPrimitive(a.timeoutSeconds))
                    })
                }
            })
            put("cooldown_seconds", JsonPrimitive(definition.cooldownSeconds))
            if (definition.maxRunsPerDay != null) {
                put("max_runs_per_day", JsonPrimitive(definition.maxRunsPerDay))
            }
            put("created_at_ms", JsonPrimitive(definition.createdAtMs.toString()))
            put("updated_at_ms", JsonPrimitive(definition.updatedAtMs.toString()))
        }
        return obj.toString()
    }

    /**
     * Round-trip parse — used when reading [me.rerere.rikkahub.workflow.db.WorkflowEntity.definitionJson]
     * back into a domain object.
     *
     * Permissive: only fails on truly unparseable JSON. Length / range / sanity checks are
     * skipped on the read path because the stored blob was already validated at write time.
     * If we ever tighten constraints later (e.g. lower MAX_ACTIONS), existing rows must
     * still be loadable so the user can see them, edit them down, or delete them; rejecting
     * silently would hide them from the Settings page and tear down their triggers without
     * explanation. Tool-name validation is also skipped — a row whose action references a
     * tool the user later disabled stays visible; the runner reports it at fire time.
     */
    fun parseStored(definitionJson: String): WorkflowDefinition? {
        val element: JsonElement = runCatching { Json.parseToJsonElement(definitionJson) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val triggerObj = obj["trigger"] as? JsonObject ?: return null
        val triggerSpec = (decodeTrigger(triggerObj) as? DecodeOk)?.value as? TriggerSpec ?: return null
        val conditions = (obj["conditions"]?.jsonArray ?: buildJsonArray { }).mapNotNull { el ->
            (el as? JsonObject)?.let { (decodeCondition(it) as? DecodeOk)?.value as? ConditionSpec }
        }
        val actions = (obj["actions"]?.jsonArray ?: return null).mapNotNull { el ->
            val ao = el as? JsonObject ?: return@mapNotNull null
            val toolName = ao["tool"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val args = ao["args"] as? JsonObject ?: buildJsonObject { }
            val timeout = ao["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60
            WorkflowAction(tool = toolName, args = args, timeoutSeconds = timeout)
        }
        if (actions.isEmpty()) return null
        val cooldown = obj["cooldown_seconds"]?.jsonPrimitive?.intOrNull ?: 0
        val maxRunsPerDay = obj["max_runs_per_day"]?.jsonPrimitive?.intOrNull
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: kotlin.uuid.Uuid.random().toString()
        val now = System.currentTimeMillis()
        return WorkflowDefinition(
            id = id,
            name = name,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            trigger = triggerSpec,
            conditions = conditions,
            actions = actions,
            cooldownSeconds = cooldown,
            maxRunsPerDay = maxRunsPerDay,
            createdAtMs = obj["created_at_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now,
            updatedAtMs = obj["updated_at_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now,
        )
    }

    // -- internal decode helpers -------------------------------------------------------

    private sealed class DecodeOutcome
    private data class DecodeOk(val value: Any) : DecodeOutcome()
    private data class DecodeErr(val err: ParseResult.Err) : DecodeOutcome()

    private fun decodeTrigger(triggerObj: JsonObject): DecodeOutcome {
        val type = triggerObj["type"]?.jsonPrimitive?.contentOrNull
            ?: return DecodeErr(ParseResult.Err("missing_trigger_type", "trigger.type is required"))
        val params = (triggerObj["params"] as? JsonObject) ?: buildJsonObject { }
        val flat = buildJsonObject {
            put("type", JsonPrimitive(type))
            for ((k, v) in params) put(k, v)
        }
        return runCatching {
            DecodeOk(strict.decodeFromJsonElement(TriggerSpec.serializer(), flat))
        }.getOrElse {
            DecodeErr(ParseResult.Err("unknown_trigger_type",
                "trigger.type='$type' not recognised or params malformed: ${it.message}"))
        }
    }

    private fun encodeTrigger(t: TriggerSpec): JsonObject {
        val flat = strict.encodeToJsonElement(TriggerSpec.serializer(), t).jsonObject
        val type = flat["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val params = buildJsonObject { for ((k, v) in flat) if (k != "type") put(k, v) }
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            put("params", params)
        }
    }

    private fun decodeCondition(condObj: JsonObject): DecodeOutcome {
        val type = condObj["type"]?.jsonPrimitive?.contentOrNull
            ?: return DecodeErr(ParseResult.Err("missing_condition_type", "condition.type is required"))
        val params = (condObj["params"] as? JsonObject) ?: buildJsonObject { }
        val flat = buildJsonObject {
            put("type", JsonPrimitive(type))
            for ((k, v) in params) put(k, v)
        }
        return runCatching {
            DecodeOk(strict.decodeFromJsonElement(ConditionSpec.serializer(), flat))
        }.getOrElse {
            DecodeErr(ParseResult.Err("unknown_condition_type",
                "condition.type='$type' not recognised or params malformed: ${it.message}"))
        }
    }

    private fun encodeCondition(c: ConditionSpec): JsonObject {
        val flat = strict.encodeToJsonElement(ConditionSpec.serializer(), c).jsonObject
        val type = flat["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val params = buildJsonObject { for ((k, v) in flat) if (k != "type") put(k, v) }
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            put("params", params)
        }
    }

    private fun sanityCheckTrigger(t: TriggerSpec): ParseResult.Err? = when (t) {
        is TriggerSpec.TimeCron -> when {
            t.cron.isNullOrBlank() && t.timeOfDay.isNullOrBlank() ->
                ParseResult.Err("invalid_trigger", "time_cron requires either cron or time_of_day")
            !t.cron.isNullOrBlank() && !t.timeOfDay.isNullOrBlank() ->
                ParseResult.Err("invalid_trigger", "time_cron: cron and time_of_day are mutually exclusive")
            !t.timeOfDay.isNullOrBlank() && !validHHmm(t.timeOfDay) ->
                ParseResult.Err("invalid_trigger", "time_cron.time_of_day must be HH:mm 24h")
            t.daysOfWeek.any { it !in 1..7 } ->
                ParseResult.Err("invalid_trigger", "time_cron.days_of_week values must be 1..7 (ISO, 1=Mon)")
            else -> null
        }
        is TriggerSpec.BatteryBelow -> if (t.thresholdPercent !in 1..100)
            ParseResult.Err("invalid_trigger", "battery_below.threshold_percent must be 1..100") else null
        is TriggerSpec.BatteryAbove -> if (t.thresholdPercent !in 1..100)
            ParseResult.Err("invalid_trigger", "battery_above.threshold_percent must be 1..100") else null
        is TriggerSpec.GeofenceEnter -> validateGeofence(t.lat, t.lng, t.radiusM)
        is TriggerSpec.GeofenceExit -> validateGeofence(t.lat, t.lng, t.radiusM)
        is TriggerSpec.AppLaunched -> if (t.packageName.isBlank())
            ParseResult.Err("invalid_trigger", "app_launched.package_name must be non-blank") else null
        is TriggerSpec.AppClosed -> if (t.packageName.isBlank())
            ParseResult.Err("invalid_trigger", "app_closed.package_name must be non-blank") else null
        is TriggerSpec.NotificationReceived ->
            // At least one filter — otherwise the workflow fires on every notification.
            if (t.packageName.isNullOrBlank() && t.titleContains.isNullOrBlank() && t.textContains.isNullOrBlank())
                ParseResult.Err("invalid_trigger",
                    "notification_received requires at least one filter (package_name, title_contains, or text_contains)")
            else null
        else -> null
    }

    private fun validateGeofence(lat: Double, lng: Double, radiusM: Int): ParseResult.Err? {
        if (lat !in -90.0..90.0) return ParseResult.Err("invalid_trigger", "geofence.lat must be -90..90")
        if (lng !in -180.0..180.0) return ParseResult.Err("invalid_trigger", "geofence.lng must be -180..180")
        if (radiusM !in WorkflowConstants.MIN_GEOFENCE_RADIUS_M..WorkflowConstants.MAX_GEOFENCE_RADIUS_M) {
            return ParseResult.Err("invalid_trigger",
                "geofence.radius_m must be ${WorkflowConstants.MIN_GEOFENCE_RADIUS_M}..${WorkflowConstants.MAX_GEOFENCE_RADIUS_M}")
        }
        return null
    }

    private fun sanityCheckCondition(c: ConditionSpec): ParseResult.Err? = when (c) {
        is ConditionSpec.TimeBetween -> when {
            !validHHmm(c.start) -> ParseResult.Err("invalid_condition", "time_between.start must be HH:mm 24h")
            !validHHmm(c.end) -> ParseResult.Err("invalid_condition", "time_between.end must be HH:mm 24h")
            else -> null
        }
        is ConditionSpec.TimeAfterSunset -> if (c.offsetMinutes !in -720..720)
            ParseResult.Err("invalid_condition", "time_after_sunset.offset_minutes must be -720..720") else null
        is ConditionSpec.TimeBeforeSunrise -> if (c.offsetMinutes !in -720..720)
            ParseResult.Err("invalid_condition", "time_before_sunrise.offset_minutes must be -720..720") else null
        is ConditionSpec.DayOfWeekIn -> if (c.days.any { it !in 1..7 })
            ParseResult.Err("invalid_condition", "day_of_week_in.days values must be 1..7 (ISO, 1=Mon)") else null
        is ConditionSpec.WifiSsidIs -> if (c.ssid.isBlank())
            ParseResult.Err("invalid_condition", "wifi_ssid_is.ssid must be non-blank") else null
        is ConditionSpec.WifiSsidIn -> if (c.ssids.isEmpty() || c.ssids.any { it.isBlank() })
            ParseResult.Err("invalid_condition", "wifi_ssid_in.ssids must be non-empty and non-blank") else null
        is ConditionSpec.BatteryAbove -> if (c.percent !in 1..100)
            ParseResult.Err("invalid_condition", "battery_above.percent must be 1..100") else null
        is ConditionSpec.BatteryBelow -> if (c.percent !in 1..100)
            ParseResult.Err("invalid_condition", "battery_below.percent must be 1..100") else null
        is ConditionSpec.ForegroundAppIs -> if (c.packageName.isBlank())
            ParseResult.Err("invalid_condition", "foreground_app_is.package_name must be non-blank") else null
        is ConditionSpec.ForegroundAppIn -> if (c.packageNames.isEmpty() || c.packageNames.any { it.isBlank() })
            ParseResult.Err("invalid_condition", "foreground_app_in.package_names must be non-empty and non-blank") else null
        else -> null
    }

    private fun validHHmm(s: String): Boolean {
        val parts = s.split(":")
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return h in 0..23 && m in 0..59
    }
}
