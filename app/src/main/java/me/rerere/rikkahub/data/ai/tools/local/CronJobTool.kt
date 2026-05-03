package me.rerere.rikkahub.data.ai.tools.local

import kotlin.uuid.Uuid
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.service.CronJobScheduler

/**
 * LLM-callable tools for the persistent cron-job system. The LLM can create, list, pause,
 * resume, and delete jobs. Jobs survive app restart + reboot via WorkManager + a boot
 * receiver.
 *
 * Two scheduling modes:
 *  - "once": fires once at a specific Unix-ms timestamp
 *  - "interval": fires every N seconds (minimum 60)
 */

private fun jobToJson(job: ScheduledJobEntity) = buildJsonObject {
    put("id", job.id)
    put("name", job.name)
    put("prompt", job.prompt)
    put("assistant_id", job.assistantId)
    put("schedule_type", job.scheduleType)
    job.atUnixMs?.let { put("at_unix_ms", it) }
    job.intervalSeconds?.let { put("interval_seconds", it) }
    put("enabled", job.enabled)
    put("created_at_ms", job.createdAtMs)
    job.lastRunAtMs?.let { put("last_run_at_ms", it) }
    job.nextRunAtMs?.let { put("next_run_at_ms", it) }
}

private fun textPart(json: String) = listOf(UIMessagePart.Text(json))

fun scheduleJobTool(
    repo: ScheduledJobRepository,
    scheduler: CronJobScheduler,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "schedule_job",
    description = """
        Schedule a recurring or one-time prompt to be sent to an assistant in the background, even
        when the app is closed. Provide either at_unix_ms for a one-shot or interval_seconds for
        a recurring job. Returns the created job id.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Human-readable name for the job")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Message that will be sent as a user message to the assistant when the job fires")
                })
                put("assistant_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional UUID of the assistant to use; defaults to the current assistant")
                })
                put("at_unix_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Unix timestamp (milliseconds) for a one-shot fire. Mutually exclusive with interval_seconds.")
                })
                put("interval_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Period in seconds for a recurring fire (minimum 60). Mutually exclusive with at_unix_ms.")
                })
            },
            required = listOf("name", "prompt")
        )
    },
    execute = { input ->
        val params = input.jsonObject
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: error("name is required")
        val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull
            ?: error("prompt is required")
        val assistantIdParam = params["assistant_id"]?.jsonPrimitive?.contentOrNull
        val atUnixMs = params["at_unix_ms"]?.jsonPrimitive?.longOrNull
        val intervalSeconds = params["interval_seconds"]?.jsonPrimitive?.intOrNull

        if (atUnixMs == null && intervalSeconds == null) {
            return@Tool textPart(buildJsonObject { put("error", "must provide at_unix_ms or interval_seconds") }.toString())
        }
        if (atUnixMs != null && intervalSeconds != null) {
            return@Tool textPart(buildJsonObject { put("error", "provide only one of at_unix_ms or interval_seconds") }.toString())
        }
        if (intervalSeconds != null && intervalSeconds < 60) {
            return@Tool textPart(buildJsonObject { put("error", "interval_seconds must be at least 60") }.toString())
        }

        val assistantId = assistantIdParam
            ?: settingsStore.settingsFlow.value.getCurrentAssistant().id.toString()

        val nowMs = System.currentTimeMillis()
        val job = ScheduledJobEntity(
            id = Uuid.random().toString(),
            name = name,
            prompt = prompt,
            assistantId = assistantId,
            scheduleType = if (atUnixMs != null) "once" else "interval",
            atUnixMs = atUnixMs,
            intervalSeconds = intervalSeconds,
            enabled = true,
            createdAtMs = nowMs,
        )
        repo.upsert(job)
        scheduler.schedule(job)
        textPart(buildJsonObject {
            put("success", true)
            put("job", jobToJson(job))
        }.toString())
    }
)

fun listJobsTool(repo: ScheduledJobRepository): Tool = Tool(
    name = "list_jobs",
    description = "List all scheduled jobs (both enabled and paused).".trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        textPart(buildJsonObject {
            put("jobs", buildJsonArray { repo.getAll().forEach { add(jobToJson(it)) } })
        }.toString())
    }
)

fun deleteJobTool(repo: ScheduledJobRepository, scheduler: CronJobScheduler): Tool = Tool(
    name = "delete_job",
    description = "Permanently delete a scheduled job by id.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Job id from list_jobs")
                })
            },
            required = listOf("id")
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: error("id is required")
        scheduler.cancel(id)
        repo.deleteById(id)
        textPart(buildJsonObject { put("success", true); put("id", id) }.toString())
    }
)

fun pauseJobTool(repo: ScheduledJobRepository, scheduler: CronJobScheduler): Tool = Tool(
    name = "pause_job",
    description = "Pause a job — keeps it in the list but stops future fires until resume_job is called.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Job id") })
            },
            required = listOf("id")
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: error("id is required")
        val job = repo.getById(id)
            ?: return@Tool textPart(buildJsonObject { put("error", "job not found: $id") }.toString())
        repo.update(job.copy(enabled = false))
        scheduler.cancel(id)
        textPart(buildJsonObject { put("success", true); put("id", id) }.toString())
    }
)

fun resumeJobTool(repo: ScheduledJobRepository, scheduler: CronJobScheduler): Tool = Tool(
    name = "resume_job",
    description = "Resume a paused job — re-enables future fires.".trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "Job id") })
            },
            required = listOf("id")
        )
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: error("id is required")
        val job = repo.getById(id)
            ?: return@Tool textPart(buildJsonObject { put("error", "job not found: $id") }.toString())
        val updated = job.copy(enabled = true)
        repo.update(updated)
        scheduler.schedule(updated)
        textPart(buildJsonObject { put("success", true); put("id", id) }.toString())
    }
)
