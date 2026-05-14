package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard

/**
 * Parses + executes mode='direct' action sequences. Each action is a single
 * `{tool: name, args: { ... }}` object; the array is run in order. Per-action
 * 60-second timeout. HARDLINE checked at every action's args BEFORE invoking.
 *
 * No inter-action templating in v1 — each action is independent. (See spec
 * §"Out of scope for v1" — that's Phase 12 Workflows territory.)
 */
class DirectModeActionRunner(
    private val json: Json,
) {

    @Serializable
    data class Action(val tool: String, val args: JsonObject)

    /** Thrown by parse() when the actions JSON is structurally invalid. */
    class ParseError(val code: String, msg: String) : Exception(msg)

    /** Outcome of running a single action. */
    sealed class StepResult {
        data class Success(val output: List<UIMessagePart>) : StepResult()
        data class Failed(val errorMessage: String) : StepResult()
        data object TimedOut : StepResult()
        data class HardlineBlocked(val reason: String) : StepResult()
        /** The action's tool is not in the available-tools list at fire time (never
         *  registered, or the assistant disabled it after the job was created). */
        data class UnknownTool(val toolName: String) : StepResult()
    }

    /** Outcome of running the whole sequence. */
    data class SequenceResult(
        val finalOutcome: String,        // success|failed|timed_out
        val errorMessage: String?,
    )

    /**
     * Execute each action sequentially. Aborts on first non-success result.
     */
    suspend fun run(actions: List<Action>, availableTools: List<Tool>): SequenceResult {
        for ((idx, action) in actions.withIndex()) {
            val result = runOne(idx, action, availableTools)
            when (result) {
                is StepResult.Success        -> continue
                is StepResult.Failed         -> return SequenceResult("failed", "action $idx: ${result.errorMessage}")
                is StepResult.TimedOut       -> return SequenceResult("timed_out", "action $idx: ${action.tool} exceeded 60s")
                is StepResult.HardlineBlocked-> return SequenceResult("failed", "action $idx: hardline:${result.reason}")
                // A direct-mode job validates its tool list at creation time, but the
                // assistant's enabled-tools set can change afterwards. If a tool the job
                // references is no longer in `availableTools` when the job fires, surface
                // it as a NAMED failure ("tool_unavailable: <toolName>") so the failed
                // run-history row tells the user exactly which tool to re-enable — rather
                // than the job appearing to fail for an opaque reason.
                is StepResult.UnknownTool    -> return SequenceResult("failed", "action $idx: tool_unavailable: ${result.toolName}")
            }
        }
        return SequenceResult("success", null)
    }

    private suspend fun runOne(
        idx: Int,
        action: Action,
        availableTools: List<Tool>,
    ): StepResult {
        val hardlineReason = HardlineCommandGuard.checkTool(action.tool, action.args.toString())
        if (hardlineReason != null) {
            Log.w(TAG, "direct-mode hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
            return StepResult.HardlineBlocked(hardlineReason)
        }
        val tool = availableTools.find { it.name == action.tool }
            ?: return StepResult.UnknownTool(action.tool)
        return try {
            val out = withTimeoutOrNull(60_000L) { tool.execute(action.args) }
            if (out == null) StepResult.TimedOut else StepResult.Success(out)
        } catch (t: Throwable) {
            Log.w(TAG, "direct-mode action $idx tool=${action.tool} threw", t)
            StepResult.Failed("${t::class.simpleName}: ${t.message.orEmpty()}".take(500))
        }
    }

    companion object {
        private const val TAG = "DirectModeActionRunner"

        /**
         * Parse a JSON string representing an array of actions. Returns a [Result] wrapping
         * the list of [Action] objects, or a [ParseError] on any structural problem.
         * Called as a static companion so tests don't need a Json instance.
         */
        fun parse(actionsJson: String): Result<List<Action>> {
            val element: JsonElement = runCatching {
                Json.parseToJsonElement(actionsJson)
            }.getOrElse {
                return Result.failure(ParseError("invalid_json", it.message ?: "JSON parse failed"))
            }
            if (element !is JsonArray) {
                return Result.failure(ParseError("not_an_array", "actions must be a JSON array"))
            }
            if (element.isEmpty()) {
                return Result.failure(ParseError("empty_actions", "actions must be non-empty"))
            }
            val parsed = ArrayList<Action>(element.size)
            for ((idx, el) in element.withIndex()) {
                if (el !is JsonObject) return Result.failure(ParseError("bad_action_shape", "action $idx is not an object"))
                val tool = (el["tool"] as? JsonPrimitive)?.contentOrNull
                    ?: return Result.failure(ParseError("missing_tool", "action $idx missing 'tool' field"))
                val args = el["args"] as? JsonObject
                    ?: return Result.failure(ParseError("missing_args", "action $idx missing 'args' object"))
                parsed += Action(tool, args)
            }
            return Result.success(parsed)
        }
    }
}
