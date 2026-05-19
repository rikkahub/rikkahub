package me.rerere.locallm.litert

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool as RikkaTool
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "LiteRtToolBridge"

/**
 * Bridges Rikka's `me.rerere.ai.core.Tool` registry to LiteRT-LM's native `@Tool`
 * mechanism. Mirrors Google AI Edge Gallery's pattern (see Gallery's `AgentTools.kt` +
 * `MobileActionsTools.kt`): a single `ToolSet` class with `@Tool`-annotated methods that
 * the SDK discovers via reflection, the model calls natively, and the runtime dispatches
 * back into our regular tool-execution path.
 *
 * Why one bridge with a single `runTool` method instead of one `@Tool` per Rikka tool:
 *   - Our tool catalog has ~55 dynamic tool factories. Generating 55 `@Tool` methods
 *     at compile time would require a KSP processor that introspects [LocalTools.kt]'s
 *     registration logic.
 *   - The SDK enumerates @Tool methods at runtime, and the model is told their NAMES +
 *     parameter shapes via the SDK's prompt template. The SDK does NOT prevent us from
 *     calling `runTool(name=..., args=...)` and letting the bridge dispatch internally —
 *     that's a valid pattern (Gallery's `AgentTools.runMcpTool` works exactly this way
 *     for the MCP-tool case).
 *   - Single bridge is simpler to test and reason about; the SDK's prompt template is
 *     still the canonical source of truth for what the model sees.
 *
 * Concurrency: the bridge holds an immutable snapshot of the current request's tools.
 * Each [LiteRtProvider.streamText] call updates the snapshot via
 * [LiteRtToolBridgeRegistry] before invoking the SDK; the @Tool method reads from there.
 */
class LiteRtToolBridge : ToolSet {

    @Tool(
        description = "Invoke a Rikka local tool by its registered name. Returns the tool's " +
            "structured JSON output as a string. Use this when you need to take an action " +
            "outside of pure conversation (e.g. running a Termux shell command, reading a " +
            "file, fetching a URL, controlling the device).",
    )
    fun runTool(
        @ToolParam(description = "The tool's registered name (e.g. termux_run_command, files_read, get_time_info).")
        name: String,
        @ToolParam(description = "A JSON string holding the tool's arguments object. Pass \"{}\" when the tool takes no arguments.")
        argsJson: String,
    ): String {
        val tool = LiteRtToolBridgeRegistry.lookup(name)
            ?: return errorEnvelope("tool_not_found", "No tool named '$name' is registered for this request.")
        val args: JsonObject = parseArgs(argsJson)
            ?: return errorEnvelope("invalid_json_args", "argsJson must be a valid JSON object, got: $argsJson")
        return runBlocking {
            runCatching {
                val parts = tool.execute(args)
                val textOnly = parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                textOnly.ifBlank { "(tool returned no text output)" }
            }.getOrElse { t ->
                Log.w(TAG, "runTool($name) threw", t)
                errorEnvelope("tool_threw", t.message ?: t::class.java.simpleName)
            }
        }
    }

    private fun parseArgs(raw: String): JsonObject? = runCatching {
        Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject
    }.getOrNull()

    private fun errorEnvelope(error: String, detail: String): String =
        buildJsonObject {
            put("error", error)
            put("detail", detail)
        }.toString()
}
