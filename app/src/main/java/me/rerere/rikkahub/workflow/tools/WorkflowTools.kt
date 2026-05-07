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
): Tool = Tool(
    name = "workflow_create",
    description = """
        Create a new workflow. Workflows are LLM-authored automations that run when their
        trigger fires (and conditions pass). The user reviews and approves the trigger +
        actions before persistence; HARDLINE applies at fire time.
        Provide the full workflow as a single 'definition' JSON object (see schema).
    """.trimIndent().replace("\n", " "),
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
    needsApproval = true,
    execute = { json ->
        val definitionEl = json.jsonObject["definition"]
            ?: return@Tool errorResponse("missing_definition", "definition object required")
        val parsed = WorkflowJson.parse(definitionEl.toString(), knownToolNamesProvider().toSet())
        when (parsed) {
            is WorkflowJson.ParseResult.Err -> errorResponse(parsed.error, parsed.detail)
            is WorkflowJson.ParseResult.Ok -> {
                val def = parsed.definition
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
): Tool = Tool(
    name = "workflow_update",
    description = """
        Replace an existing workflow's full definition. The id field in the definition
        must match an existing workflow; otherwise the call is rejected.
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
    needsApproval = true,
    execute = { json ->
        val definitionEl = json.jsonObject["definition"]
            ?: return@Tool errorResponse("missing_definition", "definition object required")
        val parsed = WorkflowJson.parse(definitionEl.toString(), knownToolNamesProvider().toSet())
        when (parsed) {
            is WorkflowJson.ParseResult.Err -> errorResponse(parsed.error, parsed.detail)
            is WorkflowJson.ParseResult.Ok -> {
                val def = parsed.definition
                val existing = repository.getById(def.id)
                    ?: return@Tool errorResponse("not_found", "no workflow with id=${def.id}; use workflow_create instead")
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
    needsApproval = true,
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
    needsApproval = true,
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
    needsApproval = true,
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
