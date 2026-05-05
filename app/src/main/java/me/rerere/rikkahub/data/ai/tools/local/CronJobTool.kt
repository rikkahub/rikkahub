package me.rerere.rikkahub.data.ai.tools.local

import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.CronExpressionParser
import me.rerere.rikkahub.service.CronJobScheduler
import java.time.ZoneId

private fun textPart(s: String) = listOf(UIMessagePart.Text(s))
private fun errEnvelope(code: String, detail: String, extra: JsonObject? = null): String =
    buildJsonObject {
        put("error", code)
        put("detail", detail)
        extra?.forEach { (k, v) -> put(k, v) }
    }.toString()

/**
 * Pure validator for schedule_job inputs. Returns null on success, a structured error
 * on the first failed check. Order of checks matters — we want to return the most
 * specific error possible (e.g. invalid_cron before bounds_inverted, since a malformed
 * cron expression makes bounds checking moot).
 */
object ScheduleJobValidator {
    data class ValidationError(val code: String, val detail: String, val extra: JsonObject? = null)

    fun validate(input: JsonObject, knownToolNames: List<String>): ValidationError? {
        val name = (input["name"] as? JsonPrimitive)?.contentOrNull
        if (name.isNullOrBlank() || name.length > 80) return ValidationError("bad_name", "name required, ≤80 chars")

        val mode = (input["mode"] as? JsonPrimitive)?.contentOrNull
        if (mode != "llm" && mode != "direct") return ValidationError("bad_mode", "mode must be 'llm' or 'direct'")

        val scheduleType = (input["schedule_type"] as? JsonPrimitive)?.contentOrNull
        if (scheduleType != "once" && scheduleType != "cron")
            return ValidationError("bad_schedule_type", "schedule_type must be 'once' or 'cron'")

        // Mode-specific
        val prompt = (input["prompt"] as? JsonPrimitive)?.contentOrNull
        val actions = input["actions"] as? kotlinx.serialization.json.JsonArray
        when (mode) {
            "llm" -> {
                if (prompt.isNullOrBlank() || actions != null)
                    return ValidationError("mutual_exclusive", "mode='llm' requires prompt and forbids actions")
            }
            "direct" -> {
                if (actions == null || prompt != null)
                    return ValidationError("mutual_exclusive", "mode='direct' requires actions and forbids prompt")
                if (actions.isEmpty())
                    return ValidationError("empty_actions", "mode='direct' requires non-empty actions array")
                for ((idx, el) in actions.withIndex()) {
                    if (el !is JsonObject) return ValidationError("bad_action_shape", "action $idx is not an object")
                    val toolName = (el["tool"] as? JsonPrimitive)?.contentOrNull
                        ?: return ValidationError("missing_tool", "action $idx missing tool")
                    val args = el["args"] as? JsonObject
                        ?: return ValidationError("missing_args", "action $idx missing args object")
                    if (toolName !in knownToolNames)
                        return ValidationError("unknown_tool", "tool '$toolName' not registered for assistant")
                    val hardline = HardlineCommandGuard.checkTool(toolName, args.toString())
                    if (hardline != null)
                        return ValidationError("hardline_blocked", "action $idx: $hardline")
                }
            }
        }

        // Type-specific
        val atUnixMs = (input["at_unix_ms"] as? JsonPrimitive)?.longOrNull
        val cronExpression = (input["cron_expression"] as? JsonPrimitive)?.contentOrNull
        when (scheduleType) {
            "once" -> {
                if (atUnixMs == null || cronExpression != null)
                    return ValidationError("mutual_exclusive", "schedule_type='once' requires at_unix_ms and forbids cron_expression")
            }
            "cron" -> {
                if (cronExpression.isNullOrBlank() || atUnixMs != null)
                    return ValidationError("mutual_exclusive", "schedule_type='cron' requires cron_expression and forbids at_unix_ms")
                CronExpressionParser.parse(cronExpression).onFailure {
                    return ValidationError("invalid_cron", it.message ?: "cron parse failed",
                        buildJsonObject {
                            put("examples", buildJsonArray {
                                add("0 9 * * MON-FRI"); add("@every 30m"); add("*/15 * * * *"); add("@daily")
                            })
                        })
                }
            }
        }

        // Bounds (cron only)
        val startAt = (input["start_at_unix_ms"] as? JsonPrimitive)?.longOrNull
        val endAt = (input["end_at_unix_ms"] as? JsonPrimitive)?.longOrNull
        if (endAt != null && startAt != null && endAt <= startAt)
            return ValidationError("bounds_inverted", "end_at_unix_ms must be > start_at_unix_ms")
        if (endAt != null && endAt < System.currentTimeMillis())
            return ValidationError("bounds_past", "end_at_unix_ms is in the past")

        // Timezone
        val tz = (input["timezone"] as? JsonPrimitive)?.contentOrNull
        if (!tz.isNullOrBlank() && runCatching { ZoneId.of(tz) }.isFailure)
            return ValidationError("bad_timezone", "unknown IANA zone: '$tz'")

        // max_runs
        val maxRuns = (input["max_runs"] as? JsonPrimitive)?.intOrNull
        if (maxRuns != null && maxRuns < 1)
            return ValidationError("max_runs_invalid", "max_runs must be >= 1")

        // catchup
        val catchup = (input["catchup"] as? JsonPrimitive)?.contentOrNull
        if (catchup != null && catchup !in setOf("skip", "fire_once", "fire_all"))
            return ValidationError("bad_catchup", "catchup must be 'skip', 'fire_once', or 'fire_all'")

        // Tags — comma-joined; reject anything that would break the LIKE query
        val tags = input["tags"] as? kotlinx.serialization.json.JsonArray
        tags?.forEach { tag ->
            val v = (tag as? JsonPrimitive)?.contentOrNull ?: return ValidationError("bad_tag", "tag is not a string")
            if (!v.matches(Regex("^[a-z0-9-]{1,40}$")))
                return ValidationError("bad_tag", "tag '$v' must be lowercase alphanumeric or dash, ≤40 chars")
        }

        return null
    }
}

// ---------- Tool factories ----------

private fun jobToJson(j: ScheduledJobEntity): JsonObject = buildJsonObject {
    put("id", j.id); put("name", j.name); put("description", j.description)
    put("assistant_id", j.assistantId)
    put("mode", j.mode); j.prompt?.let { put("prompt", it) }
    j.actionsJson?.let { put("actions", kotlinx.serialization.json.Json.parseToJsonElement(it)) }
    put("schedule_type", j.scheduleType)
    j.atUnixMs?.let { put("at_unix_ms", it) }
    j.cronExpression?.let { put("cron_expression", it) }
    j.timezone?.let { put("timezone", it) }
    j.startAtUnixMs?.let { put("start_at_unix_ms", it) }
    j.endAtUnixMs?.let { put("end_at_unix_ms", it) }
    j.maxRuns?.let { put("max_runs", it) }
    put("runs_so_far", j.runsSoFar)
    put("catchup", j.catchup)
    j.tags?.let {
        put("tags", buildJsonArray { it.split(",").filter { t -> t.isNotBlank() }.forEach { t -> add(t) } })
    }
    put("enabled", j.enabled)
    put("created_at_ms", j.createdAtMs)
    j.lastRunAtMs?.let { put("last_run_at_ms", it) }
    j.nextRunAtMs?.let { put("next_run_at_ms", it) }
}

fun scheduleJobTool(
    repo: ScheduledJobRepository,
    scheduler: CronJobScheduler,
    settingsStore: SettingsStore,
    knownToolNamesProvider: () -> List<String>,
): Tool = Tool(
    name = "schedule_job",
    description = """
        Schedule a recurring or one-shot job. Two modes: 'llm' (sends the prompt to an
        assistant at fire time, model decides what to do) and 'direct' (runs a fixed list
        of tool calls at fire time, no LLM, no tokens, deterministic).
        Two timing types: 'once' (single absolute timestamp) and 'cron' (5-field cron
        expression with aliases like @hourly, @daily, @every 30m).

        Pick 'direct' when the action is a fixed side effect ('post good morning every
        8am', 'screenshot every hour'). Free, fast, predictable.
        Pick 'llm' when the action requires reasoning ('if battery is low, message me',
        'summarize last hour of notifications').

        Cron examples: '0 9 * * MON-FRI' (weekdays 9am), '*/15 * * * *' (every 15 min),
        '@every 2h' (every 2h), '@daily' (midnight), '0 0 1 * *' (first of every month).
        Timezone defaults to the device's; pass an IANA id to override.

        catchup controls missed-window behavior on reboot/process kill: 'skip',
        'fire_once' (DEFAULT), 'fire_all' (capped at 20).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type","string") })
                put("description", buildJsonObject { put("type","string") })
                put("tags", buildJsonObject { put("type","array"); put("items", buildJsonObject { put("type","string") }) })
                put("assistant_id", buildJsonObject { put("type","string") })
                put("mode", buildJsonObject { put("type","string"); put("enum", buildJsonArray { add("llm"); add("direct") }) })
                put("prompt", buildJsonObject { put("type","string") })
                put("actions", buildJsonObject {
                    put("type","array")
                    put("items", buildJsonObject {
                        put("type","object")
                        put("properties", buildJsonObject {
                            put("tool", buildJsonObject { put("type","string") })
                            put("args", buildJsonObject { put("type","object") })
                        })
                        put("required", buildJsonArray { add("tool"); add("args") })
                    })
                })
                put("schedule_type", buildJsonObject { put("type","string"); put("enum", buildJsonArray { add("once"); add("cron") }) })
                put("at_unix_ms", buildJsonObject { put("type","integer") })
                put("cron_expression", buildJsonObject { put("type","string") })
                put("timezone", buildJsonObject { put("type","string") })
                put("start_at_unix_ms", buildJsonObject { put("type","integer") })
                put("end_at_unix_ms", buildJsonObject { put("type","integer") })
                put("max_runs", buildJsonObject { put("type","integer"); put("minimum", 1) })
                put("catchup", buildJsonObject { put("type","string"); put("enum", buildJsonArray { add("skip"); add("fire_once"); add("fire_all") }) })
            },
            required = listOf("name","mode","schedule_type"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        ScheduleJobValidator.validate(obj, knownToolNamesProvider())?.let { err ->
            return@Tool textPart(errEnvelope(err.code, err.detail, err.extra))
        }
        val mode = obj["mode"]!!.jsonPrimitive.content
        val scheduleType = obj["schedule_type"]!!.jsonPrimitive.content
        val assistantId = (obj["assistant_id"] as? JsonPrimitive)?.contentOrNull
            ?: settingsStore.settingsFlow.value.getCurrentAssistant().id.toString()
        val tagsCsv = (obj["tags"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(",")
        val nowMs = System.currentTimeMillis()

        val job = ScheduledJobEntity(
            id = Uuid.random().toString(),
            name = obj["name"]!!.jsonPrimitive.content,
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull?.take(500),
            assistantId = assistantId,
            scheduleType = scheduleType,
            atUnixMs = (obj["at_unix_ms"] as? JsonPrimitive)?.longOrNull,
            cronExpression = (obj["cron_expression"] as? JsonPrimitive)?.contentOrNull,
            timezone = (obj["timezone"] as? JsonPrimitive)?.contentOrNull,
            startAtUnixMs = (obj["start_at_unix_ms"] as? JsonPrimitive)?.longOrNull,
            endAtUnixMs = (obj["end_at_unix_ms"] as? JsonPrimitive)?.longOrNull,
            maxRuns = (obj["max_runs"] as? JsonPrimitive)?.intOrNull,
            catchup = (obj["catchup"] as? JsonPrimitive)?.contentOrNull ?: "fire_once",
            mode = mode,
            prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull,
            actionsJson = (obj["actions"] as? kotlinx.serialization.json.JsonArray)?.toString(),
            tags = tagsCsv,
            enabled = true,
            createdAtMs = nowMs,
        )
        repo.upsert(job)
        scheduler.schedule(job)
        textPart(buildJsonObject { put("success", true); put("job", jobToJson(job)) }.toString())
    },
)

fun listJobsTool(repo: ScheduledJobRepository): Tool = Tool(
    name = "list_jobs",
    description = "List scheduled jobs. Optional filters: tag, mode, enabled.".trimIndent(),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("tag", buildJsonObject { put("type","string") })
            put("mode", buildJsonObject { put("type","string"); put("enum", buildJsonArray { add("llm"); add("direct") }) })
            put("enabled", buildJsonObject { put("type","boolean") })
        })
    },
    execute = { input ->
        val obj = input.jsonObject
        val tag = (obj["tag"] as? JsonPrimitive)?.contentOrNull
        val mode = (obj["mode"] as? JsonPrimitive)?.contentOrNull
        val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull
        val rows = repo.listFiltered(tag, mode, enabled)
        textPart(buildJsonObject {
            put("jobs", buildJsonArray { rows.forEach { add(jobToJson(it)) } })
        }.toString())
    },
)

fun deleteJobTool(
    repo: ScheduledJobRepository,
    runRepo: ScheduledJobRunRepository,
    scheduler: CronJobScheduler,
): Tool = Tool(
    name = "delete_job",
    description = "Permanently delete a scheduled job and its run history.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type","string") })
            },
            required = listOf("id"),
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: error("id is required")
        scheduler.cancel(id)
        runRepo.deleteAllForJob(id)
        repo.deleteById(id)
        textPart(buildJsonObject { put("success", true); put("id", id) }.toString())
    },
)

fun pauseJobTool(repo: ScheduledJobRepository, scheduler: CronJobScheduler): Tool = Tool(
    name = "pause_job",
    description = "Pause a job — keeps the row but stops future fires until resume_job.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("id", buildJsonObject { put("type","string") }) },
            required = listOf("id"),
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
        val job = repo.getById(id) ?: return@Tool textPart(buildJsonObject {
            put("error", "not_found"); put("id", id)
        }.toString())
        repo.update(job.copy(enabled = false))
        scheduler.cancel(id)
        textPart(buildJsonObject { put("success", true); put("id", id) }.toString())
    },
)

fun resumeJobTool(repo: ScheduledJobRepository, scheduler: CronJobScheduler): Tool = Tool(
    name = "resume_job",
    description = "Resume a paused job — re-enables future fires.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("id", buildJsonObject { put("type","string") }) },
            required = listOf("id"),
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
        val job = repo.getById(id) ?: return@Tool textPart(buildJsonObject {
            put("error", "not_found"); put("id", id)
        }.toString())
        val updated = job.copy(enabled = true)
        repo.update(updated)
        scheduler.schedule(updated)
        textPart(buildJsonObject { put("success", true); put("id", id) }.toString())
    },
)

fun triggerJobNowTool(
    repo: ScheduledJobRepository,
    scheduler: CronJobScheduler,
): Tool = Tool(
    name = "trigger_job_now",
    description = "Manually fire a scheduled job immediately. Goes through the normal worker (HARDLINE checks, history row, concurrent-skip semantics). Does NOT affect the regular schedule.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("id", buildJsonObject { put("type","string") }) },
            required = listOf("id"),
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
        repo.getById(id) ?: return@Tool textPart(buildJsonObject {
            put("error", "not_found"); put("id", id)
        }.toString())
        scheduler.triggerNow(id)
        textPart(buildJsonObject {
            put("success", true)
            put("run_id", Uuid.random().toString())          // synthetic placeholder; the real row is written by the worker
            put("fired_at_ms", System.currentTimeMillis())
        }.toString())
    },
)

fun getJobHistoryTool(runRepo: ScheduledJobRunRepository): Tool = Tool(
    name = "get_job_history",
    description = "Return the most recent fires of a scheduled job, newest first.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type","string") })
                put("limit", buildJsonObject { put("type","integer"); put("minimum", 1); put("maximum", 100) })
            },
            required = listOf("id"),
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
        val limit = (input.jsonObject["limit"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 100) ?: 20
        val rows = runRepo.getRecent(id, limit)
        textPart(buildJsonObject {
            put("runs", buildJsonArray {
                rows.forEach { r -> add(buildJsonObject {
                    put("id", r.id); put("scheduled_at_ms", r.scheduledAtMs)
                    put("started_at_ms", r.startedAtMs)
                    r.finishedAtMs?.let { put("finished_at_ms", it) }
                    put("outcome", r.outcome); put("mode", r.mode)
                    r.conversationId?.let { put("conversation_id", it) }
                    r.errorMessage?.let { put("error_message", it) }
                }) }
            })
        }.toString())
    },
)
