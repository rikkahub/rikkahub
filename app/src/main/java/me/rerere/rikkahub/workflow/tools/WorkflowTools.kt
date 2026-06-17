package me.rerere.rikkahub.workflow.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.trigger.TriggerRegistry

/**
 * Phase 12 — the seven `workflow_*` tools the LLM uses to author and manage workflows.
 *
 * `knownToolNamesProvider` is a lambda over the assistant's currently-registered tool
 * names (matches the cron-job pattern at LocalTools.kt:579-580). The list is built by
 * the caller after registering everything else, so workflow_create can validate that
 * action tools actually exist on the assistant before persistence.
 *
 * Approval semantics: workflow_create / workflow_update / workflow_delete /
 * workflow_set_enabled / workflow_run are all in ToolApprovalDefaults.ALWAYS_ASK with the
 * approval prompt rendered by [WorkflowApprovalRenderer] (so the user sees readable
 * "Create workflow X — when WiFi connects, run …" instead of raw JSON). workflow_list
 * and workflow_get are read-only — no approval needed.
 */

fun workflowCreateTool(
    repository: WorkflowRepository,
    knownToolNamesProvider: () -> List<String>,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "workflow_create",
    description = """
        Create a new event-driven workflow. Workflows fire when their trigger fires AND
        every condition passes, then run the actions in order through the existing tool
        dispatcher (HARDLINE applies at fire time, every action goes through the same
        approval-bypass headless path scheduled jobs use).

        ALL WORKFLOW TRIGGERS ARE RECURRING (event-driven). There is NO one-shot mode.
        For one-shot "fire once at X time" the user wants the schedule_job tool instead
        (schedule_type: "once") — a separate cron-jobs system, not workflows.

        SUPPORTED TRIGGER TYPES (use these exact strings in trigger.type):
          time_cron — recurring schedule. Either:
              cron: "<5-field cron>"  (max once per minute), OR
              time_of_day: "HH:mm" — fires every day at that time (and every day in
                  days_of_week if provided, [1..7] ISO 1=Mon).
              ALWAYS RECURRING. To run once, delete the workflow after the first fire,
              or use the schedule_job tool with schedule_type:"once" instead.
          wifi_connected / wifi_disconnected — params: ssid (optional, null = any)
          bluetooth_device_connected / bluetooth_device_disconnected — params: device_address (optional)
          headphones_plugged / headphones_unplugged — no params
          power_connected / power_disconnected — no params (charger plug/unplug)
          battery_below / battery_above — params: threshold_percent (1..100), fires on transition
          geofence_enter / geofence_exit — params: lat, lng, radius_m (50..5000), label (optional)
          app_launched / app_closed — params: package_name
          notification_received — params: at least one of package_name, title_contains,
              text_contains, title_matches, text_matches. The *_contains fields match a
              case-insensitive substring; the *_matches fields hold a Java regex tested
              with find(). If both are set for the same field they are AND-combined (both
              must pass). Invalid regex is rejected at create time.
          boot_completed — no params
          screen_on / screen_off — no params
          manual — only fires via workflow_run tool or "Run now" button

        SUPPORTED CONDITION TYPES (AND-combined, all optional):
          time_between (start "HH:mm", end "HH:mm", wraps midnight)
          time_after_sunset / time_before_sunrise (offset_minutes)
          day_of_week_in (days [1..7] ISO, 1=Mon)
          wifi_ssid_is / wifi_ssid_in
          battery_above / battery_below (percent)
          is_charging / is_not_charging
          foreground_app_is / foreground_app_in
          screen_is_on / screen_is_off

        Every condition also accepts an optional "invert": true (default false). When set,
        that single condition's result is negated — e.g. wifi_ssid_is with invert:true
        means "when NOT on that WiFi", is_charging with invert:true means "only if NOT
        charging". Works for every condition type.

        ACTIONS: each is { tool: <existing tool name>, args: { ... }, timeout_seconds?: int }.
        Use any tool currently registered for this assistant. workflow_run is NOT allowed
        as an action (no chaining in v1).
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("definition", buildJsonObject {
                    put("type", "object")
                    put("description", "The workflow definition. Required keys: name, trigger, actions. " +
                        "Optional: description, enabled (default true), conditions (array), " +
                        "cooldown_seconds (default 0), max_runs_per_day (default unlimited), id.")
                })
            },
            required = listOf("definition"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val definitionEl = json.jsonObject["definition"]
            ?: return@Tool errorResponse("missing_definition", "definition object required")
        val parsed = WorkflowJson.parse(definitionEl.toString(), knownToolNamesProvider().toSet())
        when (parsed) {
            is WorkflowJson.ParseResult.Err -> errorResponse(parsed.error, parsed.detail)
            is WorkflowJson.ParseResult.Ok -> {
                // Stamp the authoring assistant from the caller context — the engine reads
                // this back at fire time to resolve the right tool surface deterministically.
                // If the user-declared definition pre-set an authoringAssistantId we trust it
                // (allows API-driven authoring); otherwise fall back to the calling assistant.
                val def = parsed.definition.copy(
                    authoringAssistantId = parsed.definition.authoringAssistantId
                        ?: callerContext.callerAssistantId,
                )
                runCatching { repository.upsert(def) }.fold(
                    onSuccess = {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("ok", true)
                            put("id", def.id)
                            put("name", def.name)
                        }.toString()))
                    },
                    onFailure = { errorResponse("persist_failed", it.message ?: "save failed") }
                )
            }
        }
    }
)

fun workflowListTool(repository: WorkflowRepository): Tool = Tool(
    name = "workflow_list",
    description = """
        List all workflows. Each entry includes id, name, enabled, trigger_type, and the
        last run's status + timestamp.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("enabled_only", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Only return enabled workflows (default false).")
                })
            },
        )
    },
    execute = { json ->
        val enabledOnly = json.jsonObject["enabled_only"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val all = if (enabledOnly) repository.listEnabled() else repository.listAll()
        val payload = buildJsonObject {
            put("count", all.size)
            put("items", buildJsonArray {
                for (loaded in all) {
                    val e = loaded.entity
                    val def = loaded.definition
                    add(buildJsonObject {
                        put("id", e.id)
                        put("name", e.name)
                        put("enabled", e.enabled)
                        put("trigger_type", triggerTypeKey(def))
                        put("last_run_at_ms", JsonPrimitive(e.lastRunAtMs?.toString()))
                        put("last_run_status", JsonPrimitive(e.lastRunStatus))
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun workflowGetTool(repository: WorkflowRepository): Tool = Tool(
    name = "workflow_get",
    description = """
        Fetch the full definition of one workflow plus its last 10 runs. Use this when the
        user asks to inspect or edit a specific workflow.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Workflow id (UUID).")
                })
            },
            required = listOf("id"),
        )
    },
    execute = { json ->
        val id = json.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResponse("missing_id", "id is required")
        val loaded = repository.getById(id)
            ?: return@Tool errorResponse("not_found", "no workflow with id=$id")
        val runs = repository.lastRuns(id, limit = 10)
        val payload = buildJsonObject {
            put("ok", true)
            put("definition", WorkflowJson.parseStored(loaded.entity.definitionJson)?.let {
                kotlinx.serialization.json.Json.parseToJsonElement(WorkflowJson.encode(it))
            } ?: JsonPrimitive(loaded.entity.definitionJson))
            put("last_run_at_ms", JsonPrimitive(loaded.entity.lastRunAtMs?.toString()))
            put("last_run_status", JsonPrimitive(loaded.entity.lastRunStatus))
            put("last_run_error", JsonPrimitive(loaded.entity.lastRunError))
            put("runs_today_count", loaded.entity.runsTodayCount)
            put("runs_today_date", loaded.entity.runsTodayDate)
            put("history", buildJsonArray {
                for (r in runs) {
                    add(buildJsonObject {
                        put("fired_at_ms", JsonPrimitive(r.firedAtMs.toString()))
                        put("status", r.status.name)
                        put("duration_ms", JsonPrimitive(r.durationMs.toString()))
                        put("error", JsonPrimitive(r.errorMessage))
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun workflowUpdateTool(
    repository: WorkflowRepository,
    knownToolNamesProvider: () -> List<String>,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "workflow_update",
    description = """
        Replace an existing workflow's full definition. The id field must match an existing
        workflow; otherwise the call is rejected. Same trigger / condition / action schema
        as workflow_create — see that tool's description for the full enumeration of
        supported trigger types (time_cron, wifi_*, bluetooth_*, headphones_*, power_*,
        battery_*, geofence_*, app_*, notification_received, boot_completed, screen_*,
        manual) and condition types.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("definition", buildJsonObject {
                    put("type", "object")
                    put("description", "Full workflow definition with id matching an existing row.")
                })
            },
            required = listOf("definition"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val definitionEl = json.jsonObject["definition"]
            ?: return@Tool errorResponse("missing_definition", "definition object required")
        val parsed = WorkflowJson.parse(definitionEl.toString(), knownToolNamesProvider().toSet())
        when (parsed) {
            is WorkflowJson.ParseResult.Err -> errorResponse(parsed.error, parsed.detail)
            is WorkflowJson.ParseResult.Ok -> {
                val existing = repository.getById(parsed.definition.id)
                    ?: return@Tool errorResponse("not_found", "no workflow with id=${parsed.definition.id}; use workflow_create instead")
                // Preserve the existing authoring assistant — workflow_update is for body
                // edits, not for transferring ownership. If the LLM tries to change it,
                // we ignore that and keep the original.
                val def = parsed.definition.copy(
                    authoringAssistantId = existing.definition.authoringAssistantId
                        ?: parsed.definition.authoringAssistantId
                        ?: callerContext.callerAssistantId,
                )
                runCatching { repository.upsert(def) }.fold(
                    onSuccess = {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("ok", true)
                            put("id", def.id)
                            put("name", def.name)
                        }.toString()))
                    },
                    onFailure = { errorResponse("persist_failed", it.message ?: "save failed") }
                )
            }
        }
    }
)

fun workflowDeleteTool(repository: WorkflowRepository): Tool = Tool(
    name = "workflow_delete",
    description = """
        Delete a workflow and all of its run history. The triggers it owned (geofences,
        broadcast registrations, scheduled work) are torn down on the next reconciliation.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Workflow id to delete.")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val id = json.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResponse("missing_id", "id is required")
        val ok = repository.deleteCascading(id)
        if (ok) listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("id", id)
        }.toString()))
        else errorResponse("not_found", "no workflow with id=$id")
    }
)

fun workflowSetEnabledTool(repository: WorkflowRepository): Tool = Tool(
    name = "workflow_set_enabled",
    description = """
        Enable or disable a workflow. Disabled workflows keep their definition and run
        history but their trigger receivers are unregistered (battery-friendly).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Workflow id.")
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "True to enable, false to disable.")
                })
            },
            required = listOf("id", "enabled"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val obj = json.jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResponse("missing_id", "id is required")
        val enabledStr = obj["enabled"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResponse("missing_enabled", "enabled is required")
        val enabled = enabledStr.toBooleanStrictOrNull()
            ?: return@Tool errorResponse("invalid_enabled", "enabled must be true or false")
        if (repository.getById(id) == null) {
            return@Tool errorResponse("not_found", "no workflow with id=$id")
        }
        repository.setEnabled(id, enabled)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("id", id); put("enabled", enabled)
        }.toString()))
    }
)

fun workflowRunTool(
    engine: WorkflowEngine,
    repository: WorkflowRepository,
): Tool = Tool(
    name = "workflow_run",
    description = """
        Fire a workflow synchronously regardless of its trigger. Conditions still apply,
        cooldown still applies, daily cap still applies, HARDLINE still applies. Useful
        for testing a freshly-created workflow or for the LLM to invoke a manual
        ('Manual') workflow on demand.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Workflow id to fire.")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val id = json.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errorResponse("missing_id", "id is required")
        if (repository.getById(id) == null) {
            return@Tool errorResponse("not_found", "no workflow with id=$id")
        }
        val outcome = engine.fire(id)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", outcome.status.name == "SUCCESS")
            put("status", outcome.status.name)
            put("error", JsonPrimitive(outcome.error))
            put("output_summary", outcome.summary.take(2000))
        }.toString()))
    }
)

private fun triggerTypeKey(def: WorkflowDefinition): String {
    val flat = WorkflowJson.encode(def)
    return runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(flat).jsonObject["trigger"]
            ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: "unknown"
}

private fun errorResponse(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
    }.toString()))
