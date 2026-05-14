package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

private fun encodeRun(run: SubAgentRun): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("label", run.label)
    if (run.modelId != null) put("model_id", run.modelId)
    put("run_in_background", run.runInBackground)
    put("timeout_seconds", run.timeoutSeconds)
    put("max_trips", run.maxTrips)
    put("started_at_ms", run.startedAtMs)
    if (run.finishedAtMs != null) put("finished_at_ms", run.finishedAtMs)
    if (run.result != null) put("result", run.result)
    if (run.error != null) put("error", run.error)
    put("tokens_in", run.tokensIn)
    put("tokens_out", run.tokensOut)
    put("trip_count", run.tripCount)
}

/**
 * Phase 11 — sub-agent dispatch + observation tools. The four register only when the
 * assistant has the `Sub-agents` Local Tools toggle on, AND the calling conversation is
 * NOT itself headless (the engine refuses recursive dispatch — these tools are not
 * useful inside a sub-agent run).
 */

fun subagentDispatchTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "subagent_dispatch",
    description = """
        Dispatch a focused sub-agent — a clean-context LLM run that returns a concise
        summary. Use when the task is independent (research, lookup, multi-step work)
        and would otherwise pollute your context with intermediate output, OR when the
        user explicitly asks for parallel work.

        Pass a clear, self-contained task — the sub-agent doesn't see your conversation,
        so restate any context it needs. Pass a short label so the user can recognise
        the running sub-agent. For long-running work, set run_in_background=true and
        poll with subagent_get; otherwise foreground (default) blocks until terminal.

        Concurrency caps: each assistant has its own (default 3, configurable 1-8) and
        there's a global cap of 16 across all assistants. Over-cap dispatches fail with
        a clear error — back off and retry, or wait for a slot.

        Approval-required: every dispatch needs explicit confirmation. Eligible for
        Always Allow if the user trusts the assistant to delegate freely.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject { put("type", "string") })
                put("label", buildJsonObject { put("type", "string") })
                put("model_id", buildJsonObject { put("type", "string") })
                put("system_prompt", buildJsonObject { put("type", "string") })
                put("tools", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("run_in_background", buildJsonObject { put("type", "boolean") })
                put("timeout_seconds", buildJsonObject { put("type", "integer") })
                put("max_trips", buildJsonObject { put("type", "integer") })
            },
            required = listOf("task"),
        )
    },
    needsApproval = true,
    execute = { args ->
        // Hard recursion guard — refuse the dispatch if the caller is itself a headless
        // run (cron / workflow / external-automation / another sub-agent). The engine's
        // own guard relies on a registered conversation id; cron / workflow direct-mode
        // paths have no conversation so the engine guard wouldn't fire there. Catch it here.
        if (callerContext.isHeadless) {
            return@Tool errEnv(
                "no_recursion",
                "sub-agent dispatch is not allowed from inside a headless run (cron / workflow / sub-agent / external automation). Run the work inline instead.",
            )
        }
        val params = args.jsonObject
        val task = params["task"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_task", "task is required")
        val request = SubAgentRequest(
            task = task,
            modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
            systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull,
            tools = params["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it.jsonPrimitive.contentOrNull },
            runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false,
            timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull
                ?: SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
            maxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull
                ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
            label = params["label"]?.jsonPrimitive?.contentOrNull,
        )
        // The engine's recursion guard checks `HeadlessConversations.isHeadless(parentChatId)`
        // — if the calling conversation is itself headless (cron / sub-agent / workflow /
        // external-automation) we refuse the dispatch. ToolInvocationContext propagation
        // (added 2026-05-07 stability pass) gives us the calling conversation id at tool-
        // construction time. Empty fallback is a no-knowledge sentinel — engine treats it
        // as "not in a headless run" which is correct for the legacy registration paths
        // that don't yet wire context (one-off / test).
        val parentAssistantId = callerContext.callerAssistantId.orEmpty()
        val parentChatId: String? = callerContext.callerConversationId
        when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
            is SubAgentEngine.DispatchResult.Reject ->
                return@Tool errEnv(res.error, res.detail)
            is SubAgentEngine.DispatchResult.Ok ->
                listOf(UIMessagePart.Text(encodeRun(res.run).toString()))
        }
    },
)

fun subagentListTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_list",
    description = """
        List sub-agent runs visible to this assistant. Set active_only=true to omit
        terminal runs. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("active_only", buildJsonObject { put("type", "boolean") })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val activeOnly = args.jsonObject["active_only"]?.jsonPrimitive?.booleanOrNull ?: false
        val list = registry.list(activeOnly)
        val arr = buildJsonArray {
            list.forEach { addJsonObject {
                put("id", it.id)
                put("label", it.label)
                put("status", it.status.name)
                if (it.modelId != null) put("model_id", it.modelId)
                put("started_at_ms", it.startedAtMs)
                put("trip_count", it.tripCount)
            } }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("runs", arr)
        }.toString()))
    },
)

fun subagentGetTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_get",
    description = "Fetch the full run record for a sub-agent by id. Read-only.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val run = registry.get(id)
            ?: return@Tool errEnv("unknown_id", "no sub-agent run with id $id")
        listOf(UIMessagePart.Text(encodeRun(run).toString()))
    },
)

fun subagentCancelTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_cancel",
    description = """
        Cancel a running sub-agent by id. Marks the run CANCELLED; safe to call on
        already-terminal runs (returns ok=false). Read-only from the user's perspective
        — no approval required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val cancelled = registry.requestCancel(id)
        if (cancelled) {
            registry.update(id) { it.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis()) }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", cancelled)
            put("id", id)
        }.toString()))
    },
)
